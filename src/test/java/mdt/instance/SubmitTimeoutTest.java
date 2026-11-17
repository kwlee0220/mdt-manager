package mdt.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;


/**
 * 시작·중지 요청이 받아들여지는 단계의 제한 시간({@link AbstractInstance#toSubmitTimeout})을 확인한다.
 * timeout이 null이어도 이 단계가 영원히 기다리지 않아야 한다.
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
