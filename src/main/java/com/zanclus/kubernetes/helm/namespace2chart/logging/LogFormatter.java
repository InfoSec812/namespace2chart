package com.zanclus.kubernetes.helm.namespace2chart.logging;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

public class LogFormatter extends Formatter {
	@Override
	public String format(LogRecord r) {
		SimpleFormatter simple = new SimpleFormatter();
		DateTimeFormatter formatter =
				DateTimeFormatter.ofLocalizedDateTime( FormatStyle.SHORT )
						.withLocale(Locale.getDefault())
						.withZone( ZoneId.systemDefault() );
		return String.format(
				"[%1$s] %2$s: %3$s:%4$s - %5$s%n%6$s",
				r.getLevel().getName(),
				formatter.format(r.getInstant()),
				r.getSourceClassName(),
				r.getSourceMethodName(),
				r.getMessage(),
				getStackTrace(r.getThrown())
		);
	}

	private static String getStackTrace(Throwable t) {
		if (t != null) {
			return Arrays.stream(t.getStackTrace())
					       .map(e -> String.format("%s:%s", e.getClassName(), e.getMethodName()))
					       .collect(
							       () -> new StringBuilder(),
							       (acc, item) -> acc.append("\t").append(item).append("\n"),
							       (acc, accPrime) -> acc.append(accPrime.toString())
					       ).toString();
		}
		return "";
	}
}
