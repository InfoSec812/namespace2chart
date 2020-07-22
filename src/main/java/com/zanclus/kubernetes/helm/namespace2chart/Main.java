package com.zanclus.kubernetes.helm.namespace2chart;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JsonOrgJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JsonOrgMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import com.zanclus.kubernetes.helm.namespace2chart.exceptions.APIAccessException;
import com.zanclus.kubernetes.helm.namespace2chart.exceptions.KubeConfigReadException;
import com.zanclus.kubernetes.helm.namespace2chart.exceptions.NotCurrentlyLoggedInException;
import de.danielbechler.diff.ObjectDifferBuilder;
import org.assimbly.docconverter.DocConverter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.lang.System.*;
import static java.net.http.HttpResponse.BodyHandlers.ofString;

@Command(name = "namespace2chart")
public class Main implements Callable<Integer> {

	public static final String NOT_LOGGED_IN_MESSAGE = "Log in to your cluster using either kubectl or oc and try again";

	public static final Base64.Decoder DECODER = Base64.getDecoder();

	private SSLContext sslContext;

	@Option(arity = "0..1", names = {"-S", "--insecure-ssl"}, description = "Allow self-signed, expired, and non-matching SSL certificates", defaultValue = "false", showDefaultValue = Help.Visibility.ALWAYS)
	boolean allowInsecureSsl;

	@Option(arity = "0..1", names = {"-k", "--kube-config"}, description = "The file from which to read cached Kube config", showDefaultValue = Help.Visibility.ALWAYS)
	File kubeConfigFile = new File(format("%s/.kube/config", getenv("HOME")));

	@Option(arity="1", names = {"-c", "--cluster"}, description="The URL of the Kubernetes/OpenShift cluster to target. Parsed from kube config when not set.")
	String kubeClusterUrl = null;

	@Option(arity = "0..*", names = {"-i", "--ignored"}, description="The Kubernetes/OpenShift resource types which should be ignored", showDefaultValue = Help.Visibility.ALWAYS)
	String[] ignoredResourceKinds = new String[]{
			"ReplicationController",
			"Pod",
			"Build",
			"NetworkPolicy",
			"RoleBindingRestriction",
			"ImageStreamTag",
			"ControllerRevision",
			"HorizontalPodAutoscaler",
			"AppliedClusterResourceQuota",
			"Endpoints",
			"RoleBindingRestriction",
			"Event",
			"EgressNetworkPolicy",
			"PodDisruptionBudget"
	};

	@Option(names = {"-r", "--sanitation-rules"}, description = "A JSON file containing global and kind specific JsonPath selectors and replacement/removal information")
	File overrideSanitationRulesFile;

	@Option(names = {"-v", "--verbose"}, description = "Outputs more debugging level information (Can be repeated up to 5 times for max verbosity)")
	boolean[] verbosity;

	@Option(arity = "1", names = {"-C", "--chart-name"}, description = "The name of the Helm 3 Chart to be created (default to the name of the namespace)")
	String chartName;

	@Option(arity = "1", names = {"-n", "--namespace"}, description = "The namespace from which to collect resources to be converted. Parsed from kube config when not set")
	String overrideCurrentNamespace;

	@Option(names = {"-d", "--decode-secrets"}, defaultValue = "false", description = "If set, this will cause Secrets to have their 'data' fields base64 decoded into 'stringData' fields.")
	boolean base64DecodeSecretData;

	@Option(names = {"-h", "--help"}, defaultValue = "false", usageHelp = true, description = "Output this help message.")
	boolean showHelp;

	private static Logger LOG = null;

	static {
		try {
			InputStream resourceAsStream = Main.class.getResourceAsStream("/logging.properties");
			LogManager.getLogManager().readConfiguration(resourceAsStream);
		} catch(IOException ioe) {
			System.out.println("Unable to configure logging");
		}

		LOG = LoggerFactory.getLogger(Main.class);
		Configuration.setDefaults(new Configuration.Defaults() {

			private final JsonProvider jsonProvider = new JsonOrgJsonProvider();
			private final MappingProvider mappingProvider = new JsonOrgMappingProvider();

			@Override
			public JsonProvider jsonProvider() {
				return jsonProvider;
			}

			@Override
			public MappingProvider mappingProvider() {
				return mappingProvider;
			}

			@Override
			public Set<com.jayway.jsonpath.Option> options() {
				return EnumSet.noneOf(com.jayway.jsonpath.Option.class);
			}
		});
	}

	public static void main(String[] args) {
		Main main = CommandLine.populateCommand(new Main(), args);
		if (main.showHelp) {
			CommandLine.usage(main, System.out);
			return;
		}
		int exitCode = new CommandLine(main).execute(args);
		System.exit(exitCode);
	}

	public Integer call() throws Exception {
		java.util.logging.Logger.getLogger(Main.class.getPackageName()).setLevel(computeLogLevel(verbosity));
		if (allowInsecureSsl) {
			setInsecureSslTrustManager();
		} else {
			this.sslContext = SSLContext.getDefault();
		}

		JSONObject kubeConfig;
		String namespace;
		String kubeMaster;
		String kubeToken;
		try {
			kubeConfig = readAndParseKubeConfig();

			if (LOG.isTraceEnabled()) {
				LOG.trace(toJson(kubeConfig));
			}
			namespace = kubeConfig.getString("namespace");
			kubeClusterUrl = kubeConfig.getString("kubeClusterUrl");
			kubeToken = kubeConfig.getString("kubeToken");

			LOG.debug("Namespace: {}", namespace);
			LOG.debug("Cluster URL: {}", kubeClusterUrl);
			LOG.debug("Token: {}", kubeToken);
		} catch(KubeConfigReadException e) {
			LOG.error(e.getLocalizedMessage(), e);
			return 1;
		}

		if (chartName==null) {
			chartName = namespace;
		}
		JSONObject apiSpec;
		try {
			apiSpec = retrieveSwaggerSpecification(kubeToken);
			LOG.trace("Swagger Spec: {}", toJson(apiSpec));
		} catch(NotCurrentlyLoggedInException e) {
			out.println(e.getLocalizedMessage());
			out.println(NOT_LOGGED_IN_MESSAGE);
			LOG.error(e.getLocalizedMessage(), e);
			return 3;
		}

		JSONObject exportPaths = buildExportPathList(apiSpec);
		LOG.debug("Paths: {}", toJson(exportPaths));

		JSONObject retrievedResources;
		try {
			retrievedResources = buildResourceMap(namespace, exportPaths, kubeToken);
			LOG.debug("Resources: {}", toJson(retrievedResources));
		} catch(APIAccessException|NotCurrentlyLoggedInException e) {
			out.println(e.getLocalizedMessage());
			LOG.error(e.getLocalizedMessage(), e);
			return 4;
		}

		JSONObject values = extractValuesForChart(namespace, retrievedResources);

		return 0;
	}

	/**
	 * If requested via command-line option, set the TrustManager to skip validation of SSL certificates and subject names.
	 * @throws NoSuchAlgorithmException Should not ever be thrown
	 * @throws KeyManagementException Should not ever be thrown
	 */
	private void setInsecureSslTrustManager() throws NoSuchAlgorithmException, KeyManagementException {
		var trustAllCerts = new TrustManager[] {
				new X509TrustManager() {
					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

					}

					@Override
					public X509Certificate[] getAcceptedIssuers() {
						return new X509Certificate[0];
					}
				}
		};

		sslContext = SSLContext.getInstance("TLS");
		sslContext.init(null, trustAllCerts, new SecureRandom());
		System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
	}

	/**
	 * TODO:
	 * For each resource type, compare the instances of that type with each other to find differences
	 * which should be extracted to the Helm Chart's 'Values.yaml' file
	 * @param retrievedResources The {@link JSONObject} of resources and their instances
	 * @return A {@link JSONObject} which contains the values for 'Values.yaml', the newly created templates, and the original resources.
	 */
	private JSONObject extractValuesForChart(String namespace, JSONObject retrievedResources) {
		JSONObject chart = new JSONObject();

		var differ = ObjectDifferBuilder.buildDefault();

		retrievedResources.toMap().entrySet().stream()
				.map(resourceType -> {      // This will return a JSONObject representing a template and the associated values
					JSONObject templateAndValues = new JSONObject();

					List<Map<String, Object>> resources = (List<Map<String, Object>>)resourceType.getValue();

					var first = resources.get(0);

					// TODO: Use these diffs to extract data for the Values.yaml file
					for (int i = 1; i < resources.size(); i++) {
						var diff = differ.compare(first, resources.get(i));
						diff.visit((diffNode, visit) -> System.out.printf("%s%n", new JSONObject(diffNode).toString(2)));
					}


					return templateAndValues;
				})
				.collect(                   // This will collect up the templates/values and convert them to a JSONObject representing a Chart
						JSONObject::new,
						(acc, item) -> {

						},
						(acc, accPrime) -> {

						}
				);

		return chart;
	}

	/**
	 * Iteratively request lists of resources for the appropriate resource types and store them in a JSONObject based on the
	 * type definitions from the API Specification. For example, the path '/api/v1/namespaces/{namespace}/configmaps' will
	 * return a 'List' of zero or more resource objects which should be extracted from the list. Each resource type will
	 * have a key in the resulting JSONObject, and for each key there will be a JsonArray of resource objects. The key
	 * should be the $ref to the type definition from the Swagger Schema (e.g. 'io.k8s.api.core.v1.ConfigMap')
	 *
	 * @param exportPaths The list of paths from which to retrieve lists of resources
	 * @param kubeToken The authorization bearer token for communicating with the cluster
	 * @return A {@link JSONObject} of resource type keys as {@link String} to a {@link JSONArray} of
	 *          resources as {@link JSONObject}s
	 * @throws APIAccessException If there is an error making requests to the cluster API
	 */
	private JSONObject buildResourceMap(String namespace, JSONObject exportPaths, String kubeToken) throws APIAccessException, NotCurrentlyLoggedInException {
		// TODO: Use a parallel stream to pull the various different resource types from the cluster

		final JSONObject builder = new JSONObject();

		HttpClient http = HttpClient.newHttpClient();

		exportPaths.toMap().entrySet().stream().parallel()
				.forEach(path -> {
					String requestPath = format("%s%s", kubeClusterUrl, path.getKey()).replace("{namespace}", namespace);
					URI reqPath = URI.create(requestPath);

					HashMap<String, Object> pathMap = (HashMap<String, Object>)path.getValue();
					JSONObject gvk = JsonPath.read(new JSONObject(pathMap), "$.get.x-kubernetes-group-version-kind");

					String gavStr = format("%s/%s:%s", gvk.getString("group"), gvk.getString("version"), gvk.getString("kind"));

					// Build the HTTP request for each path
					HttpRequest req = HttpRequest.newBuilder()
							                  .uri(reqPath)
							                  .header("Authorization", format("Bearer %s", kubeToken))
							                  .header("Accept", "application/json")
							                  .build();
					try {
						// Send the GET request to the API server
						LOG.debug("Attempting to retrieve '{}' containing '{}'", requestPath, gavStr);
						HttpResponse<String> response = http.send(req, ofString());

						// If a 4XX response is received, throw an exception
						if (response.statusCode()>=400) {
							LOG.warn("Received 'Unauthorized' for '{}'", reqPath);
						}

						// Iterate over the items in the List and create a JSONArray of resources for this type
						JSONObject resourceList = new JSONObject(response.body());
						List<Object> resources = resourceList.getJSONArray("items").toList().stream()
							.map(r -> new JSONObject((HashMap<String, Object>)r))
							.map(r -> r.put("kind", gvk.getString("kind")))
							.map(r -> {
								if (r.getString("kind").toLowerCase().contentEquals("secret")) {
									return this.base64DecodeSecrets(r);
								}
								return r;
							})
							.map(r -> {
								if (gvk.getString("group")!=null && gvk.getString("group").length()>0) {
									r.put("apiVersion", format("%s/%s", gvk.getString("group"), gvk.getString("version")));
								} else {
									r.put("apiVersion", gvk.getString("version"));
								}
								return r;
							})
              .map(this::removeClusterSpecificInfo)
							.collect(Collectors.toList());
						if (!resources.isEmpty()) {
							builder.put(gavStr, resources);
						}
					} catch(Exception e) {
						LOG.warn("Unable to retrieve resources from '{}'", reqPath, e);
					}
				});

		return builder;
	}

	/**
	 * Extract and decode base64 content in Secrets, remove the encoded data from the resource, then add the decoded
	 * data to the {@code stringData} field instead.
	 * @param secret A OpenShift/Kubernetes Secret in the form of a {@link JSONObject}
	 * @return A Secret as a {@link JSONObject} with the data decoded
	 */
	private JSONObject base64DecodeSecrets(JSONObject secret) {
		var entries = secret.getJSONObject("data").toMap().entrySet().stream()
				.collect(
						JSONObject::new,
						(acc, item) -> acc.put(
								item.getKey(),
								new String(DECODER.decode(((String)item.getValue()).getBytes()))
						),
						(acc, accPrime) -> acc.toMap().putAll(accPrime.toMap())
				);

		secret.remove("data");
		secret.put("stringData", entries);
		return secret;
	}

	/**
	 * Remove cluster-specific information from retrieved resource objects and return the sanitized result
	 * @param resource A {@link JSONObject} containing a single Kubernetes resource object
	 * @return A {@link JSONObject} containing the sanitized Kubernetes resource object
	 */
	private JSONObject removeClusterSpecificInfo(final JSONObject resource) {
		InputStream is;
		try {
			if (overrideSanitationRulesFile != null) {
				is = new FileInputStream(overrideSanitationRulesFile);
			} else {
				is = Main.class.getResourceAsStream("/sanitation_rules.json");
			}
			JSONObject rules = JsonPath.parse(is).json();

			JSONArray globalPaths = rules.getJSONArray("global");
			globalPaths.toList().stream()
					.forEach(rule -> {
						String pathStr = ((HashMap<String, Object>)rule).keySet().iterator().next();
						String replacement = ((HashMap<String, ? extends String>)rule).get(pathStr);
						DocumentContext ctx = JsonPath.parse(resource);
						if (replacement == null) {
							ctx.delete(pathStr);
						} else {
							ctx.set(pathStr, replacement);
						}
					});

			String apiVersionAndGroup = resource.getString("apiVersion");
			String kind = resource.getString("kind");

			if (rules.has(apiVersionAndGroup) && rules.getJSONObject(apiVersionAndGroup).has(kind)) {
				JSONArray kindRules = rules.getJSONObject(apiVersionAndGroup).getJSONArray(kind);

				kindRules.toList().stream()
						.forEach(rule -> {
							String pathStr = ((JSONObject)rule).keys().next();
							String replacement = ((JSONObject)rule).getString(pathStr);
							JsonPath.parse(resource).set(pathStr, replacement);
						});
			}
		} catch (FileNotFoundException e) {
			LOG.warn("Unable to read rules.", e);
		}
		return resource;
	}

	/**
	 * Iterate over the list of Paths from the API spec and filter down to ONLY namespaced path which return lists of resources
	 * @param apiSpec The {@link JSONObject} containing the Swagger API Spec from the cluster
	 * @return A {@link JSONObject} of REST endpoint paths as {@link String} to the details about that path and it's methods as {@link JSONObject}
	 */
	private JSONObject buildExportPathList(JSONObject apiSpec) {
		var pathMap = apiSpec.getJSONObject("paths").toMap().entrySet().stream()
				.filter(e -> e.getKey().contains("{namespace}"))
        .filter(e -> !e.getKey().contains("{name}"))
        .filter(e -> !e.getKey().contains("/watch/"))
				.filter(e -> ((HashMap<String, Object>)e.getValue()).containsKey("get"))
				.filter(e -> ((HashMap<String, Object>)((HashMap<String, Object>)e.getValue()).get("get")).containsKey("x-kubernetes-group-version-kind"))
				.collect(
						Collectors.toMap(
								Map.Entry::getKey,
								Map.Entry::getValue
						)
				);
		return new JSONObject(pathMap);
	}

	/**
	 * Use the {@link HttpClient} API and the extracted Kube/OpenShift token to retrieve the Swagger API Specification from the cluster as JSON
	 * @param kubeToken The extracted authentication bearer token with which to use for authentication to the cluster
	 * @return A {@link JSONObject} containing the Swagger API Specification for the cluster
	 * @throws NotCurrentlyLoggedInException If the token does not work or the cluster is unreachable
	 */
	private JSONObject retrieveSwaggerSpecification(String kubeToken) throws NotCurrentlyLoggedInException {
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest apiSpecReq = HttpRequest.newBuilder()
				                         .uri(URI.create(format("%s/openapi/v2?timeout=32s", kubeClusterUrl)))
																 .header("Accept", "application/json")
																 .header("Authorization", format("Bearer %s", kubeToken))
				                         .build();

		try {
			HttpResponse<String> response = http.send(apiSpecReq, ofString());
			if (response.statusCode() >= 401) {
				throw new NotCurrentlyLoggedInException("Cluster responded with Unauthorized. Please refresh your login and try again.");
			}
			return new JSONObject(response.body());
		} catch (IOException|InterruptedException e) {
			throw new NotCurrentlyLoggedInException("Unable to retrieve API details from the cluster. Check to ensure it is reachable and that your login has not timed out.", e);
		} catch (NotCurrentlyLoggedInException nclie) {
			throw nclie;
		}
	}

	/**
	 * Load the kube config from '~/.kube/config' or the specified directory and convert it from YAML to JSON
	 * @return A {@link JSONObject} containing the parsed contents of a kube config local credentials cache
	 * @throws KubeConfigReadException If the file cannot be found, opened, or parsed
	 */
	JSONObject readAndParseKubeConfig() throws KubeConfigReadException {
		try {
			String kubeConfigYaml = Files.readString(Path.of(URI.create("file:///home/dphillips/.kube/config")));
			var kubeConfig = new JSONObject(DocConverter.convertYamlToJson(kubeConfigYaml));
			String[] cfg = kubeConfig.getString("current-context").split("/");
			var kubeMaster = cfg[1];
			var namespace = Optional.ofNullable(overrideCurrentNamespace).orElse(cfg[0]);
			String clusterServerPath = format("$.clusters[?(@.name == '%s')].cluster.server", kubeMaster);
			var kubeClusterUrl = ((JSONArray) JsonPath.read(kubeConfig, clusterServerPath)).getString(0);
			var kubeToken = ((JSONArray) JsonPath.read(kubeConfig, format("$.users[?(@.name =~ /.*%s/)].user.token", kubeMaster))).getString(0);
			return new JSONObject().put("kubeClusterUrl", kubeClusterUrl).put("kubeToken", kubeToken).put("namespace", namespace);
		} catch(ArrayIndexOutOfBoundsException aiobe) {
			throw new KubeConfigReadException("Unable to parse namespace/kube master from current-context", aiobe);
		} catch(Exception e) {
			throw new KubeConfigReadException(e.getLocalizedMessage(), e);
		}
	}

	/**
	 * Set the debug log level based on the command-line flags
	 * @param verbosity a {@code boolean} array whose length indicates the desired verbosity
	 * @return A {@link Level} compatible with Log4J2 indicatign the Log Level to be set
	 */
	static Level computeLogLevel(boolean[] verbosity) {
		switch(verbosity==null?0:verbosity.length) {
			case 0:
				return Level.OFF;
			case 1:
				return Level.SEVERE;
			case 2:
				return Level.WARNING;
			case 3:
				return Level.INFO;
			case 4:
				return Level.FINE;
			case 5:
				return Level.FINER;
			case 6:
				return Level.FINEST;
			default:
				return Level.ALL;
		}
	}

	/**
	 * Converts a {@link org.json.JSONArray} to a minimized {@link String}
	 * @param array A {@link org.json.JSONArray} to be serialized
	 * @return A {@link String} representation of the object
	 */
	public static final String toJson(org.json.JSONArray array) {
		return array.toString(2);
	}

	/**
	 * Converts a {@link org.json.JSONObject} to a minimized {@link String}
	 * @param obj A {@link org.json.JSONObject} to be serialized
	 * @return A {@link String} representation of the object
	 */
	public static final String toJson(org.json.JSONObject obj) {
		return obj.toString(2);
	}
}
