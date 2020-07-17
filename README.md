= Making It Easier To Adopt Helm 3

== Overview
If you have been using OpenShift or Kubernetes for some time, you may be sick of *YAML-Hell*, like I am, and you are looking for solutions to make it easier to automate and manage resources in your cluster. The answer is Infrastructure-as-code, but how can we get there with the least amount of friction? 

IF you're using OpenShift or Kubernetes, you likely already have lots of resources in your various namespaces. Extracting that information in an automated way would help you a lot. That's the goal of this project. You will point it at your existing cluster, it will use your locally cached credentials, and it will attempt to extract that content and convert it to a Helm 3 Chart.

One of the challenges we have with these technologies is that they are evolving at a very fast rate. To help address that situation, this application **DOES NOT** define *ANY* of the resources from the cluster. It instead reads that information from the cluster itself, and it's OpenAPI/Swagger specification. This is done in the hopes that it will be easier to use this tool as container orchestration evolves.


== Build Pre-Requisites
* Java JDK (preferably GraalVM)
* Apache Maven >= 3.6.0
* GraalVM native-image (Optional, though recommended)

== Building This Tool From Source

```
mvn clean package

mvn -Pnative package        ## For GraalVM Native Image
```