package com.zanclus.kubernetes.helm.namespace2chart;

import com.zanclus.kubernetes.helm.namespace2chart.exceptions.APIAccessException;
import com.zanclus.kubernetes.helm.namespace2chart.exceptions.KubeConfigReadException;
import com.zanclus.kubernetes.helm.namespace2chart.exceptions.NotCurrentlyLoggedInException;
import jakarta.json.*;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.lang.System.*;
import static jakarta.json.JsonValue.EMPTY_JSON_OBJECT;
import static java.net.http.HttpResponse.BodyHandlers.ofInputStream;

@Command(name = "namespace2chart")
public class Main implements Callable<Integer> {

	public static final String NOT_LOGGED_IN_MESSAGE = "Log in to your cluster using either kubectl or oc and try again";

	public static final Base64.Decoder DECODER = Base64.getDecoder();


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
			"DaemonSet",
			"ReplicaSet",
			"RoleBindingRestriction",
			"ImageStreamTag",
			"ControllerRevision",
			"StatefulSet",
			"HorizontalPodAutoscaler",
			"AppliedClusterResourceQuota",
			"Endpoints",
			"RoleBindingRestriction",
			"Event",
			"EgressNetworkPolicy",
			"PodDisruptionBudget"
	};

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
			LogManager.getLogManager().readConfiguration(Main.class.getResourceAsStream("/logging.properties"));
		} catch(IOException ioe) {
			System.out.println("Unable to configure logging");
		}

		LOG = LoggerFactory.getLogger(Main.class);
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

		JsonObject kubeConfig;
		try {
			kubeConfig = loadKubeConfig();
			if (LOG.isDebugEnabled()) {
				LOG.debug(toPrettyJson(kubeConfig));
			}
		} catch(KubeConfigReadException e) {
			LOG.error(e.getLocalizedMessage(), e);
			return 1;
		}

		String namespace;
		String kubeMaster;
		try {
			JsonObject clusterDetails = extractClusterDetails(kubeConfig);
			namespace = clusterDetails.getString("namespace");
			kubeMaster = clusterDetails.getString("kubeMaster");

			LOG.debug("Namespace: {}", namespace);
			LOG.debug("Kube Master: {}", kubeMaster);
			LOG.debug("Cluster URL: {}", kubeClusterUrl);
		} catch(NotCurrentlyLoggedInException e) {
			out.println(e.getLocalizedMessage());
			out.println(NOT_LOGGED_IN_MESSAGE);
			LOG.error(e.getLocalizedMessage(), e);
			return 2;
		}

		if (chartName==null) {
			chartName = namespace;
		}

		String kubeToken;
		JsonObject apiSpec;
		try {
			kubeToken = extractKubeToken(kubeMaster, kubeConfig);
			LOG.debug("Token: {}", kubeToken);
			apiSpec = retrieveSwaggerSpecification(kubeToken);
			LOG.debug("Swagger Spec: {}", toPrettyJson(apiSpec));
		} catch(NotCurrentlyLoggedInException e) {
			out.println(e.getLocalizedMessage());
			out.println(NOT_LOGGED_IN_MESSAGE);
			LOG.error(e.getLocalizedMessage(), e);
			return 3;
		}

		JsonObject typeMap = buildTypeMap(apiSpec);

		JsonObject exportPaths = buildExportPathList(apiSpec);
		LOG.info("Path List: {}", toPrettyJson(exportPaths.keySet().stream().collect(Json::createArrayBuilder, JsonArrayBuilder::add, JsonArrayBuilder::addAll).build()));

		JsonObject retrievedResources;
		try {
			retrievedResources = buildResourceMap(namespace, exportPaths, typeMap, kubeToken);
			LOG.debug("Resources: {}", toPrettyJson(retrievedResources));
		} catch(APIAccessException|NotCurrentlyLoggedInException e) {
			out.println(e.getLocalizedMessage());
			LOG.error(e.getLocalizedMessage(), e);
			return 4;
		}

		JsonObject values = extractValuesForChart(namespace, retrievedResources);

		return 0;
	}

	/**
	 * TODO:
	 * For each resource type, compare the instances of that type with each other to find differences
	 * which should be extracted to the Helm Chart's 'Values.yaml' file
	 * @param retrievedResources The {@link JsonObject} of resources and their instances
	 * @return A {@link JsonObject} which contains the values for 'Values.yaml', the newly created templates, and the original resources.
	 */
	private JsonObject extractValuesForChart(String namespace, JsonObject retrievedResources) {
		JsonObject chart = Json.createObjectBuilder()
			.add("name", Json.createValue(Optional.ofNullable(chartName).orElse(namespace)))
			.add("values", EMPTY_JSON_OBJECT)
			.add("templates", EMPTY_JSON_OBJECT)
			.add("resources", EMPTY_JSON_OBJECT).build();

		// TODO: Investigate using JSONPatch or JSONDiff to extract differences

		return chart;
	}

	/**
	 * Iteratively request lists of resources for the appropriate resource types and store them in a JsonObject based on the
	 * type definitions from the API Specification. For example, the path '/api/v1/namespaces/{namespace}/configmaps' will
	 * return a 'List' of zero or more resource objects which should be extracted from the list. Each resource type will
	 * have a key in the resulting JsonObject, and for each key there will be a JsonArray of resource objects. The key
	 * should be the $ref to the type definition from the Swagger Schema (e.g. 'io.k8s.api.core.v1.ConfigMap')
	 *
	 * @param exportPaths The list of paths from which to retrieve lists of resources
	 * @param typeMap The Map of Types -> schemas
	 * @param kubeToken The authorization bearer token for communicating with the cluster
	 * @return A {@link JsonObject} of resource type keys as {@link String} to a {@link jakarta.json.JsonArray} of
	 *          resources as {@link JsonObject}s
	 * @throws APIAccessException If there is an error making requests to the cluster API
	 */
	private JsonObject buildResourceMap(String namespace, JsonObject exportPaths, JsonObject typeMap, String kubeToken) throws APIAccessException, NotCurrentlyLoggedInException {
		// TODO: Use a parallel stream to pull the various different resource types from the cluster

		JsonObjectBuilder builder = Json.createObjectBuilder();

		HttpClient http = HttpClient.newHttpClient();

		// Iterate over list of API endpoints to retrieve from
		for (Map.Entry<String, JsonValue> path: exportPaths.entrySet()) {
			LOG.debug("Path Key: {}", path.getKey());
			String requestPath = format("%s%s", kubeClusterUrl, path.getKey()).replace("{namespace}", namespace);
			URI reqPath = URI.create(requestPath);

			// Extract the $ref for the API Type schema
			String resourceSchema = path.getValue().asJsonObject().getJsonObject("get").getJsonObject("responses").getJsonObject("200").getJsonObject("schema").getString("$ref").replaceAll("List$", "");

			// Build the HTTP request for each path
			HttpRequest req = HttpRequest.newBuilder()
														.uri(reqPath)
														.header("Authorization", format("Bearer %s", kubeToken))
														.header("Accept", "application/json")
					                  .build();

			try {
				// Send the GET request to the API server
				LOG.debug("Attempting to retrieve '{}' containing '{}'", requestPath, resourceSchema);
				HttpResponse<InputStream> response = http.send(req, ofInputStream());

				// If a 4XX response is received, throw an exception
				if (response.statusCode()>=400) {
					throw new NotCurrentlyLoggedInException("Cluster responded with Unauthorized. Please refresh your login and try again.");
				}

				// Open the response body as an InputStream, decode the input stream as JSON
				try (InputStream bodyStream = http.send(req, ofInputStream()).body();
							JsonParser parser = Json.createParser(bodyStream)) {

					// Get the first (should be only) item
					parser.next();
					JsonObject listObject = parser.getObject();

					// Check to see if there are actually any resources or if this is an empty List
					if (listObject.containsKey("items") && !listObject.getJsonArray("items").isEmpty()) {

						boolean isSecret = base64DecodeSecretData && listObject.getString("kind").toLowerCase().startsWith("secret");
						// Process lists containing items
						builder.add(
								resourceSchema,
								listObject.getJsonArray("items").stream()
									.map(r -> {
										// Decode base64 encoded Secret data if requested in command args
										if (isSecret) {
											return this.base64DecodeSecrets(r.asJsonObject());
										}
										return (JsonObject)r;
									})
									.map(r -> {
										// Apply the group/apiVersion and kind to the items from the list
										String kind = listObject.getString("kind").replaceAll("List$", "");
										return Json.createObjectBuilder(r)
												.add("kind", Json.createValue(kind))
												.add("kind", Json.createValue(kind))
												.add("apiVersion", Json.createValue(listObject.getString("apiVersion"))).build();
									})
									.map(this::removeClusterSpecificInfo) // Remove certain metadata and status information from each item
									.collect(Json::createArrayBuilder, JsonArrayBuilder::add, JsonArrayBuilder::addAll).build());
					}
				}
			} catch(IOException|InterruptedException ioe) {
				throw new APIAccessException(format("Unable to retrieve path '%s'", path.getKey()), ioe);
			}
		}

		return builder.build();
	}

	/**
	 * Extract and decode base64 content in Secrets, remove the encoded data from the resource, then add the decoded
	 * data to the {@code stringData} field instead.
	 * @param secret A OpenShift/Kubernetes Secret in the form of a {@link JsonObject}
	 * @return A Secret as a {@link JsonObject} with the data decoded
	 */
	private JsonObject base64DecodeSecrets(JsonObject secret) {
		final JsonObjectBuilder decodedData = Json
				.createObjectBuilder(secret)
				.remove("data");

		JsonObject stringData = secret.getJsonObject("data").entrySet().stream()
				.collect(
						Json::createObjectBuilder,
						(acc, item) -> {
							JsonString val = (JsonString)item.getValue();
							acc.add(item.getKey(), new String(DECODER.decode(val.getString().getBytes())));
						},
						JsonObjectBuilder::addAll).build();

		decodedData.add("stringData", stringData);

		return decodedData.build();
	}

	/**
	 * Remove cluster-specific information from retrieved resource objects and return the sanitized result
	 * @param resource A {@link JsonObject} containing a single Kubernetes resource object
	 * @return A {@link JsonObject} containing the sanitized Kubernetes resource object
	 */
	private JsonObject removeClusterSpecificInfo(JsonObject resource) {
		// Copy the resource
		JsonObjectBuilder sanitized = Json.createObjectBuilder(resource);

		JsonObjectBuilder metadata = Json.createObjectBuilder(resource.getJsonObject("metadata"))
			  .remove("creationTimestamp")
		    .remove("generation")
		    .remove("namespace")
		    .remove("resourceVersion")
		    .remove("selfLink")
			  .remove("namespace")
				.remove("uid");

		// Some resources (e.g. ServiceAccount) do not have annotations. Only perform this operation
		// when the annotations key exists
		if (resource.getJsonObject("metadata").containsKey("annotations")) {
			JsonObjectBuilder annotations = Json.createObjectBuilder(resource.getJsonObject("metadata").getJsonObject("annotations"))
					                                .remove("kubectl.kubernetes.io/last-applied-configuration")
					                                .remove("openshift.io/build-config.name")
					                                .remove("openshift.io/build.number")
					                                .remove("openshift.io/jenkins-blueocean-log-url")
					                                .remove("openshift.io/jenkins-build-uri")
					                                .remove("openshift.io/jenkins-console-log-url")
					                                .remove("openshift.io/jenkins-log-url")
					                                .remove("openshift.io/jenkins-status-json");
			metadata.remove("annotations");
			metadata.add("annotations", annotations.build());
		}
		sanitized.remove("metadata");
		sanitized.add("metadata", metadata.build());
		sanitized.remove("status");

		return sanitized.build();
	}

	/**
	 * Iterate over the list of Paths from the API spec and filter down to ONLY namespaced path which return lists of resources
	 * @param apiSpec The {@link JsonObject} containing the Swagger API Spec from the cluster
	 * @return A {@link JsonObject} of REST endpoint paths as {@link String} to the details about that path and it's methods as {@link JsonObject}
	 */
	private JsonObject buildExportPathList(JsonObject apiSpec) {
		return Json.createObjectBuilder(
			apiSpec.getJsonObject("paths").entrySet().stream()
				.filter(e -> e.getKey().contains("{namespace}"))
				.filter(e -> !e.getKey().contains("/watch/"))
				.filter(e -> !e.getKey().contains("{name}"))
				.filter(e -> e.getValue().asJsonObject().containsKey("get"))
				.filter(e -> Arrays.stream(ignoredResourceKinds).noneMatch(i -> i.toLowerCase().contentEquals(e.getValue().asJsonObject().getJsonObject("get").getJsonObject("x-kubernetes-group-version-kind").getString("kind").toLowerCase())))
				.collect(
					Collectors.toMap(Map.Entry::getKey, e -> e.getValue().asJsonObject())
				)
			).build();
	}

	/**
	 * Retrieves a Map of Swagger definitions $refs to Type definitions
	 * @param apiSpec The {@link JsonObject} containing the Swagger API Spec from the cluster
	 * @return A {@link Map} of Swagger $refs as {@link String} to a type definition as {@link JsonObject}
	 */
	private JsonObject buildTypeMap(JsonObject apiSpec) {
		return Json.createObjectBuilder(
			apiSpec.getJsonObject("definitions").entrySet().stream()
				.collect(
					Collectors.toMap(
						e -> format("#/definitions/%s", e.getKey()),
						e -> e.getValue().asJsonObject()
					)
				)
			).build();
	}

	/**
	 * Use the {@link HttpClient} API and the extracted Kube/OpenShift token to retrieve the Swagger API Specification from the cluster as JSON
	 * @param kubeToken The extracted authentication bearer token with which to use for authentication to the cluster
	 * @return A {@link JsonObject} containing the Swagger API Specification for the cluster
	 * @throws NotCurrentlyLoggedInException If the token does not work or the cluster is unreachable
	 */
	private JsonObject retrieveSwaggerSpecification(String kubeToken) throws NotCurrentlyLoggedInException {
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest apiSpecReq = HttpRequest.newBuilder()
				                         .uri(URI.create(format("%s/openapi/v2?timeout=32s", kubeClusterUrl)))
																 .header("Accept", "application/json")
																 .header("Authorization", format("Bearer %s", kubeToken))
				                         .build();

		try {
			HttpResponse<InputStream> response = http.send(apiSpecReq, ofInputStream());
			if (response.statusCode() >= 401) {
				throw new NotCurrentlyLoggedInException("Cluster responded with Unauthorized. Please refresh your login and try again.");
			}
			try (JsonParser parser = Json.createParser(response.body())) {
				parser.next();
				return parser.getObject();
			}
		} catch (IOException|InterruptedException e) {
			throw new NotCurrentlyLoggedInException("Unable to retrieve API details from the cluster. Check to ensure it is reachable and that your login has not timed out.", e);
		} catch (NotCurrentlyLoggedInException nclie) {
			throw nclie;
		}
	}

	/**
	 * Given the kube config read from the filesystem, extract the authentication bearer token for the correct cluster/user
	 * @param kubeConfig A {@link JsonObject} containing the parsed contents of a kube config local credentials cache
	 * @return A {@link String} containing the authorization bearer token
	 * @throws NotCurrentlyLoggedInException If a corresponding token cannot be found
	 */
	private String extractKubeToken(String kubeMaster, JsonObject kubeConfig) throws NotCurrentlyLoggedInException {
		return kubeConfig
				.getJsonArray("users")
				.stream()
				.filter(u -> u.asJsonObject().getString("name").endsWith(kubeMaster))
				.findFirst()
				.orElseThrow(() -> new NotCurrentlyLoggedInException(format("There does not appear to be a cached credential token for %s", kubeMaster)))
				.asJsonObject().getJsonObject("user").getString("token");
	}

	/**
	 * Extract and store the namespace and cluster URL from the locally cached kube configuration file
	 * @param kubeConfig A {@link JsonObject} containing the parsed contents of a kube config local credentials cache
	 * @return A {@link JsonObject} containing the namespace and cluster master definitions
	 * @throws NotCurrentlyLoggedInException If a corresponding cluster URL cannot be found
	 */
	private JsonObject extractClusterDetails(JsonObject kubeConfig) throws NotCurrentlyLoggedInException {
		String[] cfg = kubeConfig.getString("current-context").split("/");
		String kubeMaster = cfg[1];
		LOG.debug("Kube Master: {}", kubeMaster);

		if (kubeClusterUrl == null) {
			kubeClusterUrl = kubeConfig.getJsonArray("clusters")
					.stream()
					.filter(c -> c.asJsonObject().getString("name").endsWith(kubeMaster))
					.map(c -> c.asJsonObject())
					.findFirst()
					.orElseThrow(() -> new NotCurrentlyLoggedInException(format("You do not appear to have cached credentials for %s", kubeMaster)))
					.getJsonObject("cluster")
					.getString("server");
		}

		String namespace = Optional.ofNullable(overrideCurrentNamespace).orElse(cfg[0]);

		return Json.createObjectBuilder().add("namespace", namespace).add("kubeMaster", kubeMaster).build();
	}

	/**
	 * Load the kube config from '~/.kube/config' or the specified directory and convert it from YAML to JSON
	 * @return A {@link JsonObject} containing the parsed contents of a kube config local credentials cache
	 * @throws KubeConfigReadException If the file cannot be found, opened, or parsed
	 */
	JsonObject loadKubeConfig() throws KubeConfigReadException {
		try {
			FileReader reader = new FileReader(kubeConfigFile);
			try (JsonParser jsonAndYamlParser = Json.createParser(reader)) {
				jsonAndYamlParser.next();
				return jsonAndYamlParser.getObject();
			}
		} catch(Exception e) {
			throw new KubeConfigReadException(e);
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
	 * Converts a {@link JsonStructure} to a minimized {@link String}
	 * @param obj A {@link JsonStructure} to be serialized
	 * @return A {@link String} representation of the object
	 */
	public static final String toJson(JsonStructure obj) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		Map<String, Object> config = new HashMap<>();
		config.put(JsonGenerator.PRETTY_PRINTING, false);
		JsonWriterFactory writerFactory = Json.createWriterFactory(config);
		JsonWriter writer = writerFactory.createWriter(baos);
		writer.write(obj);
		writer.close();
		return new String(baos.toByteArray());
	}

	/**
	 * Converts a {@link JsonStructure} to a pretty printed {@link String}
	 * @param obj A {@link JsonStructure} to be serialized
	 * @return A {@link String} representation of the object
	 */
	public static final String toPrettyJson(JsonStructure obj) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		Map<String, Object> config = new HashMap<>();
		config.put(JsonGenerator.PRETTY_PRINTING, true);
		JsonWriterFactory writerFactory = Json.createWriterFactory(config);
		JsonWriter writer = writerFactory.createWriter(baos);
		writer.write(obj);
		writer.close();
		return new String(baos.toByteArray());
	}
}
