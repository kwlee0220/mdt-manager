package mdt.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.Test;

import utils.io.FileUtils;


/**
 * 경로 설정이 homeDir을 기준으로 하는지 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTInstanceManagerConfigurationTest {
	@Test
	public void defaultsFollowHomeDirEvenWhenSetLater() {
		MDTInstanceManagerConfiguration conf = new MDTInstanceManagerConfiguration();
		File cwd = FileUtils.getCurrentWorkingDirectory();
		assertEquals(new File(cwd, "instances"), conf.getInstancesDir());

		File home = new File("/opt/mdt/manager");
		conf.setHomeDir(home);		// 나머지 경로도 homeDir을 따라간다
		assertEquals(new File(home, "instances"), conf.getInstancesDir());
		assertEquals(new File(home, "bundles"), conf.getBundlesDir());
		assertEquals(new File(home, "mdt_global_config.json"), conf.getGlobalConfigFile());
	}

	@Test
	public void relativePathsResolveAgainstHomeDirAndAbsoluteOnesStay() {
		MDTInstanceManagerConfiguration conf = new MDTInstanceManagerConfiguration();
		conf.setInstancesDir(new File("instances"));
		conf.setGlobalConfigFile(new File("config/mdt_global_config.json"));
		conf.setBundlesDir(new File("/data/bundles"));
		conf.setHomeDir(new File("/opt/mdt/manager"));		// 순서와 상관없다

		assertEquals(new File("/opt/mdt/manager/instances"), conf.getInstancesDir());
		assertEquals(new File("/opt/mdt/manager/config/mdt_global_config.json"), conf.getGlobalConfigFile());
		assertEquals(new File("/data/bundles"), conf.getBundlesDir());
	}
}
