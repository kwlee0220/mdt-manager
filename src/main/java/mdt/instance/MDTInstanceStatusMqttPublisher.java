package mdt.instance;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.Subscribe;

import mdt.client.support.MqttService;
import mdt.model.MDTModelSerDe;
import mdt.model.instance.InstanceStatusChangeEvent;

/**
 * MDTInstance 상태 변화 이벤트({@link InstanceStatusChangeEvent})를 MQTT로 발행한다.
 * <p>
 * {@code Globals.EVENT_BUS}(동기 EventBus)의 구독자다. 발행은 이 객체의 전용 스레드 하나에서 순서대로 하므로,
 * 이벤트를 발행한 스레드(인스턴스 시작·중지, 컨트롤러)는 broker를 기다리지 않는다. 발행마다 broker 확인은
 * {@link #getOperationTimeout()}까지만 기다린다. broker와 연결되어 있지 않을 때의 이벤트는 버리고 WARN을 남긴다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTInstanceStatusMqttPublisher extends MqttService {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTInstanceStatusMqttPublisher.class);
	private static final String TOPIC_STATUS_CHANGES_FORMAT = "/mdt/manager/instances/%s";
	private static final JsonMapper MAPPER = MDTModelSerDe.MAPPER;
	
	private final int m_qos;
	/** 발행 전용 스레드(하나라서 이벤트 순서가 유지된다). */
	private final ExecutorService m_publisher = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "mdt-instance-status-mqtt");
		thread.setDaemon(true);
		return thread;
	});
	
	public MDTInstanceStatusMqttPublisher(Builder builder) {
		super(builder.m_mqttServerUri);
		Preconditions.checkArgument(builder.m_qos >= 0 && builder.m_qos <= 2);
		
		m_qos = builder.m_qos;
		setLogger(s_logger);
	}
	
	@Subscribe
	public void publishStatusChangeEvent(InstanceStatusChangeEvent ev) {
		try {
			m_publisher.execute(() -> publish(ev));
		}
		catch ( RejectedExecutionException e ) {
			getLogger().warn("drop an instance status event (publisher stopped): {}", ev);
		}
	}
	
	private void publish(InstanceStatusChangeEvent ev) {
		if ( !isConnected() ) {
			getLogger().warn("drop an instance status event (not connected to MQTT broker): {}", ev);
			return;
		}
		try {
			String topic = String.format(TOPIC_STATUS_CHANGES_FORMAT, ev.getInstanceId());
			
			String jsonStr = MAPPER.writeValueAsString(ev);
			MqttMessage message = new MqttMessage(jsonStr.getBytes(StandardCharsets.UTF_8));
			message.setQos(m_qos);
			publish(topic, message, getOperationTimeout());
		}
		catch ( Exception e ) {
			getLogger().warn("failed to publish an instance status event: {}", ev, e);
		}
	}
	
	@Override
	protected void doStop() {
		// 남은 이벤트를 발행할 시간을 조금 준 뒤 연결을 끊는다.
		m_publisher.shutdown();
		try {
			if ( !m_publisher.awaitTermination(getOperationTimeout().toMillis(), TimeUnit.MILLISECONDS) ) {
				m_publisher.shutdownNow();
			}
		}
		catch ( InterruptedException e ) {
			m_publisher.shutdownNow();
			Thread.currentThread().interrupt();
		}
		super.doStop();
	}
	
	public static Builder builder() {
		return new Builder();
	}
	public static class Builder {
		private String m_mqttServerUri;
		private int m_qos = 0;
		
		public MDTInstanceStatusMqttPublisher build() {
			return new MDTInstanceStatusMqttPublisher(this);
		}
		
		public Builder mqttServerUri(String uri) {
			m_mqttServerUri = uri;
			return this;
		}
		
		public Builder qos(int qos) {
			m_qos = qos;
			return this;
		}
	}
}
