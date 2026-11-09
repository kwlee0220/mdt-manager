package mdt.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


/**
 * MDTInstance 식별자 규칙({@link AbstractJpaInstanceManager#checkInstanceId(String)})을 확인한다
 * (mdt-manager 검토 D2: id가 ""/"."이면 bundles·instances 디렉터리 전체가 지워졌다).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class InstanceIdTest {
	@ParameterizedTest
	@ValueSource(strings = { "test", "panda01", "01EATT018", "TP-75-6", "my_twin", "a" })
	public void validIdsAreAccepted(String id) {
		assertEquals(id, AbstractJpaInstanceManager.checkInstanceId(id));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", ".", "..", "../x", "a/b", "a\\\\b", "/abs", "a b", "a.b", "가나" })
	public void idsThatAreNotPlainDirectoryNamesAreRejected(String id) {
		assertThrows(IllegalArgumentException.class, () -> AbstractJpaInstanceManager.checkInstanceId(id));
	}

	@Test
	public void nullAndTooLongIdsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> AbstractJpaInstanceManager.checkInstanceId(null));
		assertEquals("x".repeat(64), AbstractJpaInstanceManager.checkInstanceId("x".repeat(64)));
		assertThrows(IllegalArgumentException.class, () -> AbstractJpaInstanceManager.checkInstanceId("x".repeat(65)));
	}
}
