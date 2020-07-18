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

import javax.json.JsonObject;
import javax.json.bind.Jsonb;
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
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.lang.String.format;
import static java.lang.System.*;

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


	private String namespace;
	private String kubeMaster;

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

		try {
			extractClusterDetails(kubeConfig);
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
		try {
			kubeToken = extractKubeToken(kubeConfig);
			LOG.debug("Token: {}", kubeToken);
		} catch(NotCurrentlyLoggedInException e) {
			out.println(e.getLocalizedMessage());
			out.println(NOT_LOGGED_IN_MESSAGE);
			LOG.error(e.getLocalizedMessage());
			return 3;
		}

		JsonObject apiSpec;
		try {
			apiSpec = retrieveSwaggerSpecification(kubeToken);
		} catch(NotCurrentlyLoggedInException e) {
			out.println(e.getLocalizedMessage());
			out.println(NOT_LOGGED_IN_MESSAGE);
			LOG.error(e.getLocalizedMessage());
			return 4;
		}

		Map<String, JsonObject> typeMap = buildTypeMap(apiSpec);

		Map<String, JsonObject> exportPaths = buildExportPathList(apiSpec);

		Map<String, List<JsonObject>> retrievedResources;
		try {
			retrievedResources = buildResourceMap(exportPaths, typeMap, kubeToken);
		} catch(APIAccessException e) {
			out.println(e.getLocalizedMessage());
			LOG.error(e.getLocalizedMessage());
			return 5;
		}

		List<JsonObject> values = extractValuesForChart(retrievedResources);

		return 0;
	}

	private List<JsonObject> extractValuesForChart(Map<String, List<JsonObject>> retrievedResources) {
		// TODO:

		return null;
	}

	private Map<String, List<JsonObject>> buildResourceMap(Map<String, JsonObject> exportPaths, Map<String, JsonObject> typeMap, String kubeToken) throws APIAccessException {
		// TODO:

		return null;
	}

	/**
	 * Iterate over the list of Paths from the API spec and filter down to ONLY namespaced path which return lists of resources
	 * @param apiSpec
	 * @return
	 */
	private Map<String, JsonObject> buildExportPathList(JsonObject apiSpec) {
		return apiSpec.getJsonObject("paths").entrySet().stream()
				.filter(e -> e.getKey().contains("{namespace}"))
				.filter(e -> !e.getKey().contains("/watch/"))
				.filter(e -> !e.getKey().contains("{name}"))
				.collect(
						Collectors.toMap(
								e -> e.getKey(),
								e -> e.getValue().asJsonObject()
						)
				);
	}

	private Map<String, JsonObject> buildTypeMap(JsonObject apiSpec) {
		return apiSpec.getJsonObject("definitions").entrySet().stream()
				.collect(
						Collectors.toMap(
								e -> format("#/definitions/%s", e.getKey()),
								e -> e.getValue().asJsonObject()
						)
				);
	}

	private JsonObject retrieveSwaggerSpecification(String kubeToken) throws NotCurrentlyLoggedInException {
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest apiSpecReq = HttpRequest.newBuilder()
				                         .uri(URI.create(format("%s/openapi/v2?timeout=32s", kubeClusterUrl)))
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

	private String extractKubeToken(JsonObject kubeConfig) throws NotCurrentlyLoggedInException {
		return kubeConfig
				.getJsonArray("users")
				.stream()
				.filter(u -> u.asJsonObject().getString("name").endsWith(kubeMaster))
				.findFirst()
				.orElseThrow(() -> new NotCurrentlyLoggedInException(format("There does not appear to be a cached credential token for %s", kubeMaster)))
				.asJsonObject().getJsonObject("user").getString("token");
	}

	private void extractClusterDetails(JsonObject kubeConfig) throws NotCurrentlyLoggedInException {
		String[] cfg = kubeConfig.getString("current-context").split("/");
		kubeMaster = cfg[1];
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

		if (namespace == null) {
			namespace = cfg[0];
		}
	}

	JsonObject loadKubeConfig() throws KubeConfigReadException {
		try {
			String inputConfig = Files.readAllLines(kubeConfigFile.toPath()).stream().collect(Collectors.joining("\n"));
			String jsonConfig = DocConverter.convertYamlToJson(inputConfig);
			return JsonbBuilder.newBuilder().build().fromJson(jsonConfig, JsonObject.class);
		} catch(Exception e) {
			throw new KubeConfigReadException(e);
		}
	}

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
