package mdt.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;


/**
 * 시작·중지 요청이 받아들여지는 단계의 제한 시간({@link AbstractInstance#toSubmitTimeout})을 확인한다
 * (mdt-manager 검토 D13: timeout이 null이면 이 단계가 영원히 기다렸다).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SubmitTimeoutTest {
	@Test
	public void submitWaitIsAlwaysBounded() {
		assertEquals(AbstractInstance.DEFAULT_SUBMIT_TIMEOUT, AbstractInstance.toSubmitTimeout(null));
		assertEquals(AbstractInstance.DEFAULT_SUBMIT_TIMEOUT, AbstractInstance.toSubmitTimeout(Duration.ofMinutes(10)));
		assertEquals(Duration.ofSeconds(5), AbstractInstance.toSubmitTimeout(Duration.ofSeconds(5)));
	}
}
