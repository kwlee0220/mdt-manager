package mdt.controller;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import utils.io.EnvironmentFileLoader;
import utils.stream.KeyValueFStream;
import utils.jdbc.JdbcUtils;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTManagerEnvironment implements EnvironmentPostProcessor, Ordered {
    private static final DeferredLog s_deferredLog = new DeferredLog();
    
	private static final String ENV_FILE_ENV_VAR = "ENV_FILE";
	private static final String ENV_FILE_PROPERTY = "env.file";
	private static final String ENV_FILE_NAME = "config/env.file";
	
	private static Map<String,Object> s_environmentVariables = Map.of();
	
	/** 로그에서 값을 가릴 변수 이름: {@code PASS}/{@code SECRET}/{@code TOKEN}/{@code CREDENTIAL}을 포함(대소문자 무시). */
	private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(PASS|SECRET|TOKEN|CREDENTIAL).*");
	
	/**
	 * 로그에 남길 변수 값을 만든다.
	 * <p>
	 * 이름이 비밀 정보로 보이면({@link #SECRET_KEY}) 값 전체를 {@code ****}로, 아니면 값 안의 URL 비밀번호
	 * ({@code user:pw@}, {@code password=...})만 가린다. 2026-11-05 이전에는 env 파일의 모든 값을 그대로 INFO 로그에
	 * 남겼다.
	 *
	 * @param key	변수 이름.
	 * @param value	변수 값.
	 * @return	로그용 값. {@code value}가 null이면 null.
	 */
	public static String maskValue(String key, String value) {
		if ( value == null ) {
			return null;
		}
		return SECRET_KEY.matcher(key).matches() ? "****" : JdbcUtils.maskPassword(value);
	}
	
	/**
	 * 변수 맵의 값을 {@link #maskValue(String, String)}로 가린 사본을 반환한다(로그용).
	 *
	 * @param variables	변수 맵.
	 * @return	값을 가린 새 맵(순서 유지).
	 */
	public static Map<String,String> maskVariables(Map<String,String> variables) {
		Map<String,String> masked = new LinkedHashMap<>();
		variables.forEach((k, v) -> masked.put(k, maskValue(k, v)));
		return masked;
	}
	
	public static Map<String,Object> getVariables() {
		return Collections.unmodifiableMap(s_environmentVariables);
	}
	
	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        application.addListeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
        	Log actualLogger = LogFactory.getLog(MDTManagerEnvironment.class);
			s_deferredLog.replayTo(actualLogger);
		});
        
		String fromProp = System.getProperty(ENV_FILE_PROPERTY);
		String fromEnv = System.getenv(ENV_FILE_ENV_VAR);
		String path = firstNonBlank(fromProp, fromEnv, ENV_FILE_NAME);
		
		File envFile = new File(path);
		if ( !envFile.exists() ) {
			s_deferredLog.info("no environment file found at: " + envFile.getAbsolutePath());
			return;
		}

		s_deferredLog.info("loading environment variables from file: " + envFile.getAbsolutePath());
		try {
			EnvironmentFileLoader envLoader = EnvironmentFileLoader.from(new File(path));
			Map<String,String> variables = envLoader.load();
			
			s_environmentVariables
					= KeyValueFStream.from(variables)
									.peek(v -> s_deferredLog.info(String.format("  - EnvVar: %s=%s", v.key(), maskValue(v.key(), v.value()))))
									.mapValue(v -> (Object)v)
									.toMap();
			MapPropertySource source = new MapPropertySource("mdtEnv", s_environmentVariables);
			env.getPropertySources().addFirst(source);
		}
		catch ( IOException e ) {
			// 2026-11-05 이전에는 아무 흔적 없이 삼켰다.
			s_deferredLog.warn("failed to load environment variables from file: " + envFile.getAbsolutePath(), e);
		}
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
