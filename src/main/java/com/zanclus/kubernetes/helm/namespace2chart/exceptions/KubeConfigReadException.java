package com.zanclus.kubernetes.helm.namespace2chart.exceptions;

public class KubeConfigReadException extends Exception {

	private static final String MESSAGE = "Unable to read Kube configuration.";

	public KubeConfigReadException() {
		super(MESSAGE);
	}

	public KubeConfigReadException(Throwable t) {
		super(MESSAGE, t);
	}
}
