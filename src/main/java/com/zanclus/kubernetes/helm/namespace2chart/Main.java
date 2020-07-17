package com.zanclus.kubernetes.helm.namespace2chart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.zanclus.kubernetes.helm.namespace2chart.exceptions.APIAccessException;
import com.zanclus.kubernetes.helm.namespace2chart.exceptions.KubeConfigReadException;
import com.zanclus.kubernetes.helm.namespace2chart.exceptions.NotCurrentlyLoggedInException;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.core.models.AuthorizationValue;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.*;
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

	private ObjectMapper yamlMapper;

	private String namespace;
	private String kubeMaster;

	public static void main(String[] args) {
		int exitCode = new CommandLine(new Main()).execute(args);
		exit(exitCode);
	}

	public Integer call() throws Exception {
		setVerbosity();

		configYamlParser();

		JsonNode kubeConfig;
		try {
			kubeConfig = loadKubeConfig();
			if (LOG.isDebugEnabled()) {
				LOG.debug(kubeConfig.toPrettyString());
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
			kubeToken = exctractKubeToken(kubeConfig);
			LOG.debug("Token: {}", kubeToken);
		} catch(NotCurrentlyLoggedInException e) {
			out.println(e.getLocalizedMessage());
			out.println(NOT_LOGGED_IN_MESSAGE);
			LOG.error(e.getLocalizedMessage());
			return 3;
		}

		SwaggerParseResult apiSpec;
		try {
			apiSpec = retrieveSwaggerSpecification(kubeToken);
		} catch(NotCurrentlyLoggedInException e) {
			out.println(e.getLocalizedMessage());
			out.println(NOT_LOGGED_IN_MESSAGE);
			LOG.error(e.getLocalizedMessage());
			return 4;
		}

		Map<String, Schema> typeMap = buildTypeMap(apiSpec);

		Map<String, PathItem> exportPaths = buildExportPathList(apiSpec);

		Map<String, List> retrievedResources;
		try {
			retrievedResources = buildResourceMap(exportPaths, typeMap, kubeToken);
		} catch(APIAccessException e) {
			out.println(e.getLocalizedMessage());
			LOG.error(e.getLocalizedMessage());
			return 5;
		}

		List<JsonNode> values = extractValuesForChart(retrievedResources);

		return 0;
	}

	private List<JsonNode> extractValuesForChart(Map<String, List> retrievedResources) {
		// TODO: Iterate through the resource types, identify fields match/don't match and extract the non-matching items
		// for use in the Values file.
		return null;
	}

	private Map<String, List> buildResourceMap(Map<String, PathItem> exportPaths, Map<String, Schema> typeMap, String kubeToken) throws APIAccessException {
		Map<String, List> retrievedResources = new HashMap<>();
		// TODO: implement iterative resource retrieval from the K8s/OCP API master
		return retrievedResources;
	}

	private Map<String, PathItem> buildExportPathList(SwaggerParseResult apiSpec) {
		return apiSpec.getOpenAPI().getPaths().entrySet().stream()
				.filter(e -> e.getKey().contains("{namespace}"))
				.filter(e -> !e.getKey().contains("/watch"))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private Map<String, Schema> buildTypeMap(SwaggerParseResult apiSpec) {
		return apiSpec.getOpenAPI().getComponents().getSchemas().entrySet().stream()
				.filter(e -> e.getValue().getExtensions() != null)
				.filter(e -> e.getValue().getExtensions().size() > 0)
				.collect(Collectors.toMap(k -> format("#/definitions/%s", k.getKey()), Map.Entry::getValue));
	}

	private SwaggerParseResult retrieveSwaggerSpecification(String kubeToken) throws NotCurrentlyLoggedInException {
		// Retrieve and parse Swagger Spec from cluster
		AuthorizationValue apiAuth = new AuthorizationValue()
				                             .keyName("Authorization")
				                             .value(format("Bearer %s", kubeToken))
				                             .type("header");

		SwaggerParseResult apiSpec;
		try {
			apiSpec = new OpenAPIParser().readLocation("${kubeCluster}/openapi/v2?timeout=32s", Arrays.asList(apiAuth), new ParseOptions());
		} catch (NullPointerException npe) {
			throw new NotCurrentlyLoggedInException("Your cached credentials appears to be expired or invalid. Please log in with kubectl or oc and try again.");
		}

		if (apiSpec == null || apiSpec.getOpenAPI() == null || apiSpec.getOpenAPI().getComponents() == null || apiSpec.getOpenAPI().getComponents().getSchemas() == null) {
			throw new NotCurrentlyLoggedInException("Your cached credentials appears to be expired or invalid. Please log in with kubectl or oc and try again.");
		}
		return apiSpec;
	}

	private String exctractKubeToken(JsonNode kubeConfig) throws NotCurrentlyLoggedInException {
		Spliterator<JsonNode> users = Spliterators.spliteratorUnknownSize(kubeConfig.get("users").iterator(), Spliterator.ORDERED);
		Stream<JsonNode> userStream = StreamSupport.stream(users, false);
		Optional<JsonNode> cachedUser = userStream.filter(n -> n.get("name").asText().endsWith(kubeMaster)).findFirst();
		if (!cachedUser.isPresent()) {
			throw new NotCurrentlyLoggedInException(format("There does not appear to be a cached credential token for %s", kubeMaster));
		}
		return cachedUser.get().get("user").get("token").asText();
	}

	private void extractClusterDetails(JsonNode kubeConfig) throws NotCurrentlyLoggedInException {
		String[] cfg = kubeConfig.get("current-context").asText().split("/");
		kubeMaster = cfg[1];
		LOG.debug("Kube Master: {}", kubeMaster);

		if (kubeClusterUrl == null) {
			Spliterator<JsonNode> clusters = Spliterators.spliteratorUnknownSize(kubeConfig.get("clusters").iterator(), Spliterator.CONCURRENT);
			Stream<JsonNode> clusterStream = StreamSupport.stream(clusters, true);
			Optional<JsonNode> currentCluster = clusterStream.filter(n -> n.get("name").asText().endsWith(kubeMaster)).findFirst();
			if (!currentCluster.isPresent()) {
				throw new NotCurrentlyLoggedInException(format("You do not appear to have cached credentials for %s", kubeMaster));
			}
			kubeClusterUrl = currentCluster.get().get("cluster").get("server").asText();
		}

		if (namespace == null) {
			namespace = cfg[0];
		}
	}

	JsonNode loadKubeConfig() throws KubeConfigReadException {
		try {
			InputStream reader = new FileInputStream(kubeConfigFile);
			return yamlMapper.readValue(reader, JsonNode.class);
		} catch(Exception e) {
			throw new KubeConfigReadException(e);
		}

	}

	void configYamlParser() {
		yamlMapper = new ObjectMapper(new YAMLFactory());
		yamlMapper.findAndRegisterModules();
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
