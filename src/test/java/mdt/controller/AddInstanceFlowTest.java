package mdt.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.google.common.eventbus.Subscribe;

import mdt.Globals;
import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.InstanceStatusChangeEvent;


/**
 * {@code POST /instance-manager/instances}의 흐름을 확인한다(mdt-manager 검토 D28·D29).
 *
 * @author Kang-Woo Lee (ETRI)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class AddInstanceFlowTest {
	@TempDir File m_bundles;
	private AbstractJpaInstanceManager m_manager;
	private MDTInstanceManagerConfiguration m_conf;
	private MockMvc m_mvc;
	private final List<String> m_events = new CopyOnWriteArrayList<>();
	private final Object m_listener = new Object() {
		@Subscribe
		public void on(InstanceStatusChangeEvent ev) {
			m_events.add(ev.getStatusChange());
		}
	};

	@BeforeEach
	public void setUp() {
		m_manager = mock(AbstractJpaInstanceManager.class);
		m_conf = new MDTInstanceManagerConfiguration();
		when(m_manager.getBundlesDir()).thenReturn(m_bundles);
		when(m_manager.getConfiguration()).thenReturn(m_conf);

		MDTInstanceManagerController ctrl = new MDTInstanceManagerController();
		ReflectionTestUtils.setField(ctrl, "m_instanceManager", m_manager);
		m_mvc = MockMvcBuilders.standaloneSetup(ctrl)
								.setControllerAdvice(new MDTInstanceManagerExceptionAdvice())
								.build();
		Globals.EVENT_BUS.register(m_listener);
	}

	@AfterEach
	public void tearDown() {
		Globals.EVENT_BUS.unregister(m_listener);
	}

	@Test
	public void brokenZipEndsWithAddFailedAndLeavesNothing() throws Exception {
		m_mvc.perform(multipart("/instance-manager/instances").file(bundle("not a zip".getBytes()))
															.param("id", "t1"))
			.andExpect(status().isBadRequest());
		assertEquals(List.of("ADDING", "ADD_FAILED"), m_events);
		assertEquals(0, m_bundles.list().length, "per-request work directory must be removed");
	}

	@Test
	public void autoStartFailureStillRegisters() throws Exception {
		m_conf.setAutoStart(true);
		JpaInstance inst = mock(JpaInstance.class);
		when(m_manager.addInstance(eq("t1"), anyInt(), any(File.class))).thenReturn(inst);
		when(m_manager.getInstance("t1")).thenReturn(inst);
		when(inst.getInstanceDescriptor()).thenReturn(descriptor());
		doThrow(new TimeoutException("slow")).when(inst).start(null, null);

		m_mvc.perform(multipart("/instance-manager/instances").file(bundle(zip())).param("id", "t1"))
			.andExpect(status().isCreated());
		assertEquals(List.of("ADDING", "ADDED"), m_events);
		assertEquals(0, m_bundles.list().length);
	}

	@Test
	public void duplicateIdIsConflictWithAddFailed() throws Exception {
		when(m_manager.addInstance(eq("t1"), anyInt(), any(File.class)))
				.thenThrow(new ResourceAlreadyExistsException("MDTInstance", "id=t1"));
		m_mvc.perform(multipart("/instance-manager/instances").file(bundle(zip())).param("id", "t1"))
			.andExpect(status().isConflict());
		assertEquals(List.of("ADDING", "ADD_FAILED"), m_events);
	}

	@Test
	public void uploadFileNameIsNotUsed() throws Exception {
		// 이름 없는 업로드(2026-11-05 이전에는 NPE)와 경로를 담은 이름도 문제없이 처리된다.
		JpaInstance inst = mock(JpaInstance.class);
		when(m_manager.addInstance(eq("t1"), anyInt(), any(File.class))).thenReturn(inst);
		when(m_manager.getInstance("t1")).thenReturn(inst);
		when(inst.getInstanceDescriptor()).thenReturn(descriptor());
		MockMultipartFile weird = new MockMultipartFile("bundle", "../../etc/passwd", "application/zip", zip());
		m_mvc.perform(multipart("/instance-manager/instances").file(weird).param("id", "t1"))
			.andExpect(status().isCreated());
		assertTrue(new File(m_bundles.getParentFile(), "etc").exists() == false);
	}

	private static InstanceDescriptor descriptor() {
		InstanceDescriptor desc = new InstanceDescriptor();
		desc.setId("t1");
		desc.setStatus(mdt.model.instance.MDTInstanceStatus.STOPPED);
		desc.setAasId("https://example.com/aas/t1");
		desc.setAssetType(mdt.model.sm.info.MDTAssetType.Machine);
		return desc;
	}

	private static MockMultipartFile bundle(byte[] bytes) {
		return new MockMultipartFile("bundle", "t1.zip", "application/zip", bytes);
	}

	private static byte[] zip() throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try ( ZipOutputStream zos = new ZipOutputStream(bos) ) {
			zos.putNextEntry(new ZipEntry("model.json"));
			zos.write("{}".getBytes());
			zos.closeEntry();
		}
		return bos.toByteArray();
	}
}
