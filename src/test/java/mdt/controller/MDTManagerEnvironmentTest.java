package mdt.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;


/**
 * 로그용 환경 변수 값 가리기({@link MDTManagerEnvironment#maskValue})를 확인한다(mdt-manager 검토 D4).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTManagerEnvironmentTest {
	@Test
	public void secretLookingNamesAreMaskedEntirely() {
		for ( String key: List.of("DB_PASSWORD", "MDT_KEY_STORE_PASSWORD", "api_token", "ClientSecret", "AWS_CREDENTIALS") ) {
			assertEquals("****", MDTManagerEnvironment.maskValue(key, "s3cr3t"), key);
		}
	}

	@Test
	public void passwordsInsideUrlsAreMasked() {
		String masked = MDTManagerEnvironment.maskValue("DB_URL", "jdbc:postgresql://mdt:s3cr3t@localhost:5432/mdt");
		assertFalse(masked.contains("s3cr3t"), masked);
		assertEquals("http://localhost:12985", MDTManagerEnvironment.maskValue("MDT_URL", "http://localhost:12985"));
		assertNull(MDTManagerEnvironment.maskValue("X", null));
	}

	@Test
	public void maskVariablesKeepsOrderAndDoesNotTouchTheSource() {
		Map<String,String> vars = new LinkedHashMap<>();
		vars.put("MDT_URL", "http://mdt:12985");
		vars.put("MDT_KEY_STORE_PASSWORD", "s3cr3t");
		Map<String,String> masked = MDTManagerEnvironment.maskVariables(vars);
		assertEquals(List.of("MDT_URL", "MDT_KEY_STORE_PASSWORD"), List.copyOf(masked.keySet()));
		assertEquals("****", masked.get("MDT_KEY_STORE_PASSWORD"));
		assertEquals("s3cr3t", vars.get("MDT_KEY_STORE_PASSWORD"));
	}
}
