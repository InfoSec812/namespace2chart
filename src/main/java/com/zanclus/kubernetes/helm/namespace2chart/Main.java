package com.zanclus.kubernetes.helm.namespace2chart;

import com.zanclus.kubernetes.helm.namespace2chart.exceptions.APIAccessException;
import com.zanclus.kubernetes.helm.namespace2chart.exceptions.KubeConfigReadException;
import com.zanclus.kubernetes.helm.namespace2chart.exceptions.NotCurrentlyLoggedInException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.assimbly.docconverter.DocConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.bind.JsonbBuilder;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.lang.System.*;
import static javax.json.JsonValue.EMPTY_JSON_OBJECT;

@Command(name = "namespace2chart")
public class Main implements Callable<Integer> {
	public static final String NOT_LOGGED_IN_MESSAGE = "Log in to your cluster using either kubectl or oc and try again";
	@Option(arity = "0..1", names = {"-k", "--kube-config"}, description = "The file from which to read cached Kube config (~/.kube/config)")
	File kubeConfigFile = new File(format("%s/.kube/config", getenv("HOME")));

	@Option(arity="1", names = {"-c", "--cluster"}, description="The URL of the Kubernetes/OpenShift cluster to target (defaults to currently logged in cluster from ~/.kube/config)")
	String kubeClusterUrl = null;

	@Option(arity = "0..*", names = {"-i", "--ignored"}, description="The Kubernetes/OpenShift resource types which should be ignored (default: ReplicationController, Pod).")
	String[] ignoredResourceKinds = new String[]{ "ReplicationController", "Pod" };

	@Option(names = {"-v", "--verbose"}, description = "Outputs more debugging level information (Can be repeated up to 5 times for max verbosity)")
	boolean[] verbosity;

	@Option(arity = "1", names = {"-C", "--chart-name"}, description = "The name of the Helm 3 Chart to be created (default to the name of the namespace)")
	String chartName;

	@Option(arity = "1", names = {"-n", "--namespace"}, description = "The namespace from which to collect resources to be converted (defaults to the currently selected namespace from ~/.kube/config)")
	String userSelectedNamespace;

	private static final Logger LOG = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		int exitCode = new CommandLine(new Main()).execute(args);
		exit(exitCode);
	}

	public Integer call() throws Exception {
		setVerbosity();

		JsonObject kubeConfig;
		try {
			kubeConfig = loadKubeConfig();
			if (LOG.isDebugEnabled()) {
				LOG.debug(kubeConfig.toString());
			}
		} catch(KubeConfigReadException e) {
			LOG.error(e.getLocalizedMessage());
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
			LOG.error(e.getLocalizedMessage());
			return 2;
		}

		String kubeToken;
		JsonObject apiSpec;
		try {
			kubeToken = extractKubeToken(kubeMaster, kubeConfig);
			LOG.debug("Token: {}", kubeToken);
			apiSpec = retrieveSwaggerSpecification(kubeToken);
		} catch(NotCurrentlyLoggedInException e) {
			out.println(e.getLocalizedMessage());
			out.println(NOT_LOGGED_IN_MESSAGE);
			LOG.error(e.getLocalizedMessage());
			return 3;
		}

		JsonObject typeMap = buildTypeMap(apiSpec);

		JsonObject exportPaths = buildExportPathList(apiSpec);

		JsonObject retrievedResources;
		try {
			retrievedResources = buildResourceMap(namespace, exportPaths, typeMap, kubeToken);
		} catch(APIAccessException e) {
			out.println(e.getLocalizedMessage());
			LOG.error(e.getLocalizedMessage());
			return 4;
		}

		JsonObject values = extractValuesForChart(namespace, retrievedResources);

		return 0;
	}

	/**
	 * WIP:
	 * TODO:
	 * For each resource type, compare the instances of that type with each other to find differences
	 * which should be extracted to the Helm Chart's 'Values.yaml' file
	 * @param retrievedResources The {@link Map} of resources and their instances
	 * @return A {@link JsonObject} which contains the values for 'Values.yaml', the newly created templates, and the original resources.
	 */
	private JsonObject extractValuesForChart(String namespace, JsonObject retrievedResources) {
		JsonObject chart = EMPTY_JSON_OBJECT;
		chart.put("name", Json.createValue(Optional.ofNullable(chartName).orElse(namespace)));
		chart.put("values", EMPTY_JSON_OBJECT);
		chart.put("templates", EMPTY_JSON_OBJECT);
		chart.put("resources", EMPTY_JSON_OBJECT);

		// TODO:

		return chart;
	}

	/**
	 * WIP:
	 * TODO:
	 * Iteratively request lists of resources for the appropriate resource types and store them in a Map based on the
	 * type definitions from the API Specification
	 * @param exportPaths The list of paths from which to retrieve lists of resources
	 * @param typeMap The Map of Types -> schemas
	 * @param kubeToken The authorization bearer token for communicating with the cluster
	 * @return A {@link JsonObject} of resource type keys as {@link String} to a {@link javax.json.JsonArray} of
	 *          resources as {@link JsonObject}s
	 * @throws APIAccessException If there is an error making requests to the cluster API
	 */
	private JsonObject buildResourceMap(String namespace, JsonObject exportPaths, JsonObject typeMap, String kubeToken) throws APIAccessException {
		// TODO:

		return null;
	}

	/**
	 * Remove cluster-specific information from retrieved resource objects and return the sanitized result
	 * @param resource A {@link JsonObject} containing a single Kubernetes resource object
	 * @return A {@link JsonObject} containing the sanitized Kubernetes resource object
	 */
	private JsonObject removeClusterSpecificInfo(JsonObject resource) {
		// Copy the resource
		JsonObject sanitized = Json.createObjectBuilder(resource).build();

		sanitized.getJsonObject("metadata").getJsonObject("annotations").remove("kubectl.kubernetes.io/last-applied-configuration");
		sanitized.getJsonObject("metadata").remove("creationTimestamp");
		sanitized.getJsonObject("metadata").remove("generation");
		sanitized.getJsonObject("metadata").remove("namespace");
		sanitized.getJsonObject("metadata").remove("resourceVersion");
		sanitized.getJsonObject("metadata").remove("selfLink");
		sanitized.getJsonObject("metadata").remove("uid");
		sanitized.replace("status", EMPTY_JSON_OBJECT);

		return sanitized;
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
				.collect(
					Collectors.toMap(
						e -> e.getKey(),
						e -> e.getValue().asJsonObject()
					)
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
			return JsonbBuilder.newBuilder()
					       .build()
					       .fromJson(
					       		http.send(apiSpecReq, HttpResponse.BodyHandlers.ofString())
												.body(),
					          JsonObject.class
					       );
		} catch (IOException|InterruptedException e) {
			throw new NotCurrentlyLoggedInException("Unable to retrieve API details from the cluster. Check to ensure it is reachable and that your login has not timed out.", e);
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

		String namespace = Optional.ofNullable(userSelectedNamespace).orElse(cfg[0]);

		return Json.createObjectBuilder().add("namespace", namespace).add("kubeMaster", kubeMaster).build();
	}

	/**
	 * Load the kube config from '~/.kube/config' or the specified directory and convert it from YAML to JSON
	 * @return A {@link JsonObject} containing the parsed contents of a kube config local credentials cache
	 * @throws KubeConfigReadException If the file cannot be found, opened, or parsed
	 */
	JsonObject loadKubeConfig() throws KubeConfigReadException {
		try {
			String inputConfig = Files.readAllLines(kubeConfigFile.toPath()).stream().collect(Collectors.joining("\n"));
			String jsonConfig = DocConverter.convertYamlToJson(inputConfig);
			return JsonbBuilder.newBuilder().build().fromJson(jsonConfig, JsonObject.class);
		} catch(Exception e) {
			throw new KubeConfigReadException(e);
		}
	}

	/**
	 * Set the debug log level based on the command-line flags
	 */
	void setVerbosity() {
		switch(verbosity==null?0:verbosity.length) {
			case 0:
		    Configurator.setRootLevel(Level.FATAL);
		    break;
		  case 1:
		    Configurator.setRootLevel(Level.ERROR);
		    break;
		  case 2:
		    Configurator.setRootLevel(Level.WARN);
		    break;
		  case 3:
		    Configurator.setRootLevel(Level.INFO);
		    break;
		  case 4:
		    Configurator.setRootLevel(Level.DEBUG);
		    break;
		  default:
		    Configurator.setRootLevel(Level.ALL);
		}
	}
}
