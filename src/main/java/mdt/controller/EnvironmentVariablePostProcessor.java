package mdt.controller;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import utils.io.EnvironmentFileLoader;
import utils.stream.KeyValueFStream;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class EnvironmentVariablePostProcessor implements EnvironmentPostProcessor, Ordered {
	private static final Logger s_logger = LoggerFactory.getLogger(EnvironmentVariablePostProcessor.class);
	
	private static final String ENV_FILE_ENV_VAR = "ENV_FILE";
	private static final String ENV_FILE_PROPERTY = "env.file";
	private static final String ENV_FILE_NAME = "config/env.file";
	
	private static Map<String,Object> s_environmentVariables = Map.of();
	
	public static Map<String,Object> getEnvironmentVariables() {
		return s_environmentVariables;
	}
	
	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
		String fromProp = System.getProperty(ENV_FILE_PROPERTY);
		String fromEnv = System.getenv(ENV_FILE_ENV_VAR);
		String path = firstNonBlank(fromProp, fromEnv, ENV_FILE_NAME);
		
		try {
			System.out.println("loading environment variables from file: " + path);
			s_logger.info("loading environment variables from file: {}", path);
			
			EnvironmentFileLoader envLoader = EnvironmentFileLoader.from(new File(path));
			LinkedHashMap<String, String> variables = envLoader.load();
			
			s_environmentVariables = KeyValueFStream.from(variables)
													.mapValue(v -> (Object)v)
													.toMap();
			MapPropertySource source = new MapPropertySource("mdtEnv", s_environmentVariables);
			env.getPropertySources().addFirst(source);
		}
		catch ( IOException e ) { }
	}
	
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}
	
	private static String firstNonBlank(String... arr) {
		for ( String s : arr ) {
			if ( s != null && !s.isBlank() )
				return s;
		}
		return null;
	}
}
