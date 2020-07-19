package com.zanclus.kubernetes.helm.namespace2chart.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.*;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;


public class CustomLoggingConfigurator {

	public static void configLogging() {
		ConfigurationBuilder<BuiltConfiguration> builder
				= ConfigurationBuilderFactory.newConfigurationBuilder();

		AppenderComponentBuilder stdout = builder.newAppender("stdout", "Console");

		LayoutComponentBuilder standard = builder.newLayout("PatternLayout");
		standard.addAttribute("pattern", "%highlight{[%level] %d{ISO8601}: %l - %msg%n}%ex{full}");

		stdout.add(standard);
		builder.add(stdout);

		RootLoggerComponentBuilder rootLogger = builder.newRootLogger(Level.WARN);
		rootLogger.add(builder.newAppenderRef("stdout"));
		builder.add(rootLogger);
		BuiltConfiguration loggingConfig = builder.build();
		Configurator.initialize(loggingConfig);
	}
}
