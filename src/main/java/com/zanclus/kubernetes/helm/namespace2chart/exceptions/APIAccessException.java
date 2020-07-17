package com.zanclus.kubernetes.helm.namespace2chart.exceptions;

public class APIAccessException extends Throwable {

	public APIAccessException(String message, Throwable t) {
		super(message, t);
	}

	public APIAccessException(String message) {
		super(message);
	}
}
