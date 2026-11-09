package mdt.instance.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultAssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodel;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.eventbus.Subscribe;

import mdt.Globals;
import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.instance.MqttConfiguration;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.InvalidResourceStatusException;
import mdt.repository.Repositories;


/**
 * 외부 인스턴스의 등록·해제가 DB에 반영되는지 확인한다(mdt-manager 검토 D11).
 * <p>
 * 결함은 트랜잭션 밖에서 호출할 때만 드러나므로, 테스트 트랜잭션 없이(NOT_SUPPORTED) 실행한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@DataJpaTest
@Import(Repositories.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
	"spring.datasource.url=jdbc:h2:mem:d11;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
	"spring.jpa.hibernate.ddl-auto=create-drop",
})
public class ExternalInstanceManagerRegistrationTest {
	@Autowired Repositories m_repos;
	@TempDir File m_home;
	private ExternalInstanceManager m_manager;

	@BeforeEach
	public void setUp() throws Exception {
		MDTInstanceManagerConfiguration mgrConf = new MDTInstanceManagerConfiguration();
		mgrConf.setHomeDir(m_home);
		mgrConf.setInstancesDir(new File(m_home, "instances"));
		mgrConf.setBundlesDir(new File(m_home, "bundles"));
		ExternalConfiguration extConf = new ExternalConfiguration();
		extConf.setCheckInterval(Duration.ofHours(1));
		extConf.setConnectionTimeout(Duration.ofHours(1));
		m_manager = new ExternalInstanceManager(mgrConf, extConf, m_repos, new MqttConfiguration());

		JpaInstanceDescriptor desc = JpaInstanceDescriptor.from("ext1", aas(), infoSubmodel());
		desc.setStatus(MDTInstanceStatus.STOPPED);		// 실제 등록 경로(addInstanceDescriptor)와 같게
		m_repos.instances().save(desc);
	}

	@AfterEach
	public void tearDown() {
		m_manager.shutdown();
		m_repos.instances().deleteAll();
	}

	@Test
	public void unregisterIsPersisted() throws Exception {
		m_manager.register("ext1", "https://localhost:10443/api/v3.0");
		JpaInstanceDescriptor registered = m_repos.instances().findByInstanceId("ext1").orElseThrow();
		assertEquals(MDTInstanceStatus.RUNNING, registered.getStatus());
		assertEquals("https://localhost:10443/api/v3.0", registered.getBaseEndpoint());

		m_manager.unregister("ext1");
		JpaInstanceDescriptor unregistered = m_repos.instances().findByInstanceId("ext1").orElseThrow();
		assertEquals(MDTInstanceStatus.STOPPED, unregistered.getStatus());
		assertNull(unregistered.getBaseEndpoint());
	}

	/**
	 * 등록·해제가 상태 변화 이벤트(MQTT 발행의 원천)를 내보낸다(검토 D12: 이전에는 jar/external이 이벤트를 내지 않았다).
	 */
	@Test
	public void registrationPostsStatusEvents() throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		Object listener = new Object() {
			@Subscribe
			public void on(InstanceStatusChangeEvent ev) {
				events.add(ev.getInstanceId() + ":" + ev.getStatusChange());
			}
		};
		Globals.EVENT_BUS.register(listener);
		try {
			m_manager.register("ext1", "https://localhost:10443/api/v3.0");
			m_manager.unregister("ext1");
		}
		finally {
			Globals.EVENT_BUS.unregister(listener);
		}
		assertEquals(List.of("ext1:RUNNING", "ext1:STOPPED"), events);
	}

	/**
	 * 실행 중인 인스턴스는 지울 수 없다(검토 D12: STOPPING도 거부).
	 */
	@Test
	public void runningOrStoppingInstanceCannotBeRemoved() throws Exception {
		m_manager.register("ext1", "https://localhost:10443/api/v3.0");
		assertThrows(InvalidResourceStatusException.class, () -> m_manager.removeInstance("ext1"));
		m_manager.transitionStatus("ext1", MDTInstanceStatus.STOPPING, null);
		assertThrows(InvalidResourceStatusException.class, () -> m_manager.removeInstance("ext1"));
	}

	/**
	 * 이미 등록된 id로 등록하면 아무것도 건드리기 전에 거부한다(검토 D29).
	 */
	@Test
	public void addingAnExistingIdIsRejected() {
		assertThrows(mdt.model.ResourceAlreadyExistsException.class,
					() -> m_manager.addInstance("ext1", -1, new File(m_home, "no-bundle")));
	}

	/**
	 * 실행 인자가 없거나 깨졌어도 등록하면 마지막 갱신 시각이 기록된다(검토 D35: 이전에는 빈 catch로 삼켜, 다음
	 * health check에서 곧바로 STOPPED가 되었다).
	 */
	@Test
	public void registerRebuildsBrokenArguments() throws Exception {
		JpaInstanceDescriptor desc = m_repos.instances().findByInstanceId("ext1").orElseThrow();
		desc.setArguments("not json");
		m_repos.instances().save(desc);

		long before = System.currentTimeMillis();
		m_manager.register("ext1", "https://localhost:10443/api/v3.0");
		String args = m_repos.instances().findByInstanceId("ext1").orElseThrow().getArguments();
		long lastModified = mdt.model.MDTModelSerDe.getJsonMapper()
										.readValue(args, ExternalInstanceArguments.class).getLastModified();
		org.junit.jupiter.api.Assertions.assertTrue(lastModified >= before, args);
	}

	@Test
	public void invalidConfigurationFailsFast() {
		ExternalConfiguration conf = new ExternalConfiguration();
		assertThrows(IllegalStateException.class, () -> ExternalInstanceManager.checkExternalConfigurationValidity(conf));
		conf.setConnectionTimeout(Duration.ofMinutes(1));
		assertThrows(IllegalStateException.class, () -> ExternalInstanceManager.checkExternalConfigurationValidity(conf));
		conf.setCheckInterval(Duration.ZERO);
		assertThrows(IllegalArgumentException.class, () -> ExternalInstanceManager.checkExternalConfigurationValidity(conf));
		conf.setCheckInterval(Duration.ofMillis(500));		// 1초 미만도 허용(밀리초 단위 스케줄)
		ExternalInstanceManager.checkExternalConfigurationValidity(conf);
	}

	/**
	 * 시작 자리는 원자적으로 하나만 차지된다(검토 D36: 이전에는 두 start()가 모두 상태 검사를 통과했다).
	 */
	@Test
	public void startClaimIsExclusive() throws Exception {
		assertEquals(MDTInstanceStatus.STOPPED, m_manager.claimStart("ext1"));
		assertThrows(InvalidResourceStatusException.class, () -> m_manager.claimStart("ext1"));
		m_manager.releaseStartClaim("ext1", MDTInstanceStatus.STOPPED);
		assertEquals(MDTInstanceStatus.STOPPED, m_repos.instances().findByInstanceId("ext1").orElseThrow().getStatus());
	}

	/**
	 * 시작 요청이 곧바로 실패하면(외부 인스턴스는 직접 시작할 수 없음) 차지한 STARTING을 되돌린다.
	 */
	@Test
	public void failedStartRequestReleasesTheClaim() {
		assertThrows(UnsupportedOperationException.class, () -> m_manager.getInstance("ext1").start(null, null));
		assertEquals(MDTInstanceStatus.STOPPED, m_repos.instances().findByInstanceId("ext1").orElseThrow().getStatus());
	}

	/**
	 * 지운 인스턴스는 REMOVED 이벤트를 낸다(검토 D38: 트랜잭션 밖이면 곧바로, 안이면 커밋 뒤).
	 */
	@Test
	public void removeInstancePostsRemovedAfterDeleting() throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		Object listener = new Object() {
			@Subscribe
			public void on(InstanceStatusChangeEvent ev) {
				events.add(ev.getStatusChange());
			}
		};
		Globals.EVENT_BUS.register(listener);
		try {
			m_manager.removeInstance("ext1");
		}
		finally {
			Globals.EVENT_BUS.unregister(listener);
		}
		assertEquals(List.of("REMOVED"), events);
		assertTrue(m_repos.instances().findByInstanceId("ext1").isEmpty());
	}

	private static org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor aas() {
		return new DefaultAssetAdministrationShellDescriptor.Builder()
								.id("https://example.com/aas/ext1")
								.idShort("ext1")
								.submodelDescriptors(List.of())
								.build();
	}

	private static org.eclipse.digitaltwin.aas4j.v3.model.Submodel infoSubmodel() {
		return new DefaultSubmodel.Builder()
						.id("ext1/sm/InformationModel")
						.idShort("InformationModel")
						.submodelElements(new DefaultSubmodelElementCollection.Builder()
								.idShort("MDTInfo")
								.value(new DefaultProperty.Builder().idShort("AssetType").value("Machine").build())
								.build())
						.build();
	}
}
