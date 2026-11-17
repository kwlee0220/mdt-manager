package mdt.instance;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import mdt.model.instance.InstanceStatusChangeEvent;


/**
 * 상태 이벤트 발행이 이벤트를 보낸 스레드를 막지 않는지 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTInstanceStatusMqttPublisherTest {
	@Test
	public void publishingNeverBlocksTheCaller() {
		// 연결되지 않은 broker: 이벤트는 버려지지만 호출자는 곧바로 돌아와야 한다.
		MDTInstanceStatusMqttPublisher publisher = MDTInstanceStatusMqttPublisher.builder()
																				.mqttServerUri("tcp://127.0.0.1:1")
																				.qos(1)
																				.build();
		assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
			for ( int i = 0; i < 100; ++i ) {
				publisher.publishStatusChangeEvent(InstanceStatusChangeEvent.RUNNING("t" + i, "https://x"));
			}
		});
	}
}
