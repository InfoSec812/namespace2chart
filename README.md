# Making It Easier To Adopt Helm 3

## This project is still a Work-In-Progress and not yet completely functional. I'd love to have people help out where it makes sense though!

## Overview
If you have been using OpenShift or Kubernetes for some time, you may be sick of *YAML-Hell*, like I am, and you are looking for solutions to make it easier to automate and manage resources in your cluster. The answer is Infrastructure-as-code, but how can we get there with the least amount of friction? 

IF you're using OpenShift or Kubernetes, you likely already have lots of resources in your various namespaces. Extracting that information in an automated way would help you a lot. That's the goal of this project. You will point it at your existing cluster, it will use your locally cached credentials, and it will attempt to extract that content and convert it to a Helm 3 Chart.

One of the challenges we have with these technologies is that they are evolving at a very fast rate. To help address that situation, this application **DOES NOT** define *ANY* of the resources from the cluster. It instead reads that information from the cluster itself, and it's OpenAPI/Swagger specification. This is done in the hopes that it will be easier to use this tool as container orchestration evolves.


## Project Goals

* :heavy_check_mark:The application **MUST** extract resources from the namespace, remove certain cluster-specific metadata/annotations, and reset the `status` field in the output YAML
* :heavy_check_mark:The application **MUST** be able to use the locally cached credentials from someplace like `~/.kube/config`
* :heavy_check_mark:The tool **MUST** have minimal required configuration options. Ideally, if you are already logged in with `oc` or `kubectl` it should run without ANY parameters
* :heavy_check_mark:The application **SHOULD** extract all resource types from the target namespace, excepting the ignored types. (This includes Custom Resources)
* :black_square_button:The application **SHOULD** run on all three major operating systems: Windows, Linux, and MacOS as a native binary (Works on Linux, not yet tested on Mac/Windows)
* :black_square_button:The application **SHOULD** find all commonalities between resources of the same *kind* and extract the differences into the `Values.yaml` of the resultant Chart
* :black_square_button:The application **SHOULD** create tests in the Helm chart to ensure that the output would reproduce the content it was extracted from (excepting the cluster-specific metadata/annotations/status)
* :black_square_button:The application **SHOULD** perform validation of the schema of the templates to ensure those templates match the schema extracted from the target cluster

## Build Pre-Requisites
* Java JDK (preferably GraalVM)
* Apache Maven >= 3.6.0
* GraalVM native-image (Optional, though recommended)

## Building This Tool From Source

```
mvn clean package

mvn -Pnative package        ## For GraalVM Native Image
```

## Usage

You will need to have already logged on to your cluster with the command-line tool (e.g. `oc` or `kubectl`).

```
Usage: namespace2chart [-dhv] [-k[=<kubeConfigFile>]] [-c=<kubeClusterUrl>]
                       [-C=<chartName>] [-n=<overrideCurrentNamespace>] [-i
                       [=<ignoredResourceKinds>...]]...
  -c, --cluster=<kubeClusterUrl>
                         The URL of the Kubernetes/OpenShift cluster to target.
                           Parsed from kube config when not set.
  -C, --chart-name=<chartName>
                         The name of the Helm 3 Chart to be created (default to
                           the name of the namespace)
  -d, --decode-secrets   If set, this will cause Secrets to have their 'data'
                           fields base64 decoded into 'stringData' fields.
  -h, --help             Output this help message.
  -i, --ignored[=<ignoredResourceKinds>...]
                         The Kubernetes/OpenShift resource types which should
                           be ignored
                           Default: [ReplicationController, Pod, Build,
                           NetworkPolicy, DaemonSet, ReplicaSet,
                           RoleBindingRestriction, ImageStreamTag,
                           ControllerRevision, StatefulSet,
                           HorizontalPodAutoscaler,
                           AppliedClusterResourceQuota, Endpoints,
                           RoleBindingRestriction, Event, EgressNetworkPolicy,
                           PodDisruptionBudget]
  -k, --kube-config[=<kubeConfigFile>]
                         The file from which to read cached Kube config
                           Default: /home/dphillips/.kube/config
  -n, --namespace=<overrideCurrentNamespace>
                         The namespace from which to collect resources to be
                           converted. Parsed from kube config when not set
  -v, --verbose          Outputs more debugging level information (Can be
                           repeated up to 5 times for max verbosity)
```

## Frequently Asked Questions

* Why is this tool written in Java and not Go?
  * Because of mind share... IMHO, Go is still a pretty niche language. The Java language has an amazing number of people who can contribute.
* What about ... 
  * [Chartify](https://github.com/kubepack/chartify)
    * It only supports a [few resource types](https://github.com/kubepack/chartify/blob/master/pkg/kube_objects.go#L20)
* Shouldn't I be practicing Infrastructure-as-Code, so isn't this kinda backwards?
  * Simple answer: **YES**! 
  * More complex answer: Often our deployments evolve quickly and organically, and we need to extract what we have been experimenting with to create that Infrastructure-as-Code