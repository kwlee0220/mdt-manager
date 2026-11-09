package mdt.instance.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.instance.MqttConfiguration;
import mdt.model.MDTModelSerDe;


/**
 * Docker 백엔드 설정 처리를 확인한다(mdt-manager 검토 D19·D20).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DockerInstanceManagerConfigTest {
	@TempDir File m_home;

	@Test
	public void defaultEndpointFormatHasDoubleSlash() throws Exception {
		DockerInstanceManager mgr = new DockerInstanceManager(mgrConf(), dockerConf("mdt/faaast-starter"), null,
																new MqttConfiguration());
		try {
			String ep = mgr.toServiceEndpoint(10443);
			assertTrue(ep.startsWith("https://") && ep.endsWith(":10443/api/v3.0"), ep);
		}
		finally {
			mgr.shutdown();
		}
	}

	@Test
	public void imageNameIsRequired() {
		assertThrows(NullPointerException.class,
					() -> new DockerInstanceManager(mgrConf(), dockerConf(null), null, new MqttConfiguration()));
	}

	@Test
	public void executionArgumentsCarryPortAndReadOldJson() throws Exception {
		DockerExecutionArguments args = new DockerExecutionArguments("mdt/faaast-starter", 10443);
		String json = MDTModelSerDe.getJsonMapper().writeValueAsString(args);
		assertEquals(args, MDTModelSerDe.getJsonMapper().readValue(json, DockerExecutionArguments.class));
		assertEquals(10443, args.getPort());

		// 2026-11-05 이전에 저장된 인자에는 port가 없다 → 임의 포트.
		DockerExecutionArguments old = MDTModelSerDe.getJsonMapper()
										.readValue("{\"imageRepoName\":\"x\"}", DockerExecutionArguments.class);
		assertNull(old.getPort());
		assertNull(new DockerExecutionArguments("x", -1).getPort());
	}

	private MDTInstanceManagerConfiguration mgrConf() {
		MDTInstanceManagerConfiguration conf = new MDTInstanceManagerConfiguration();
		conf.setHomeDir(m_home);
		conf.setInstancesDir(new File(m_home, "instances"));
		conf.setBundlesDir(new File(m_home, "bundles"));
		return conf;
	}

	private static DockerConfiguration dockerConf(String imageName) {
		DockerConfiguration conf = new DockerConfiguration();
		conf.setDockerEndpoint("http://127.0.0.1:1");
		conf.setImageName(imageName);
		return conf;
	}
}
