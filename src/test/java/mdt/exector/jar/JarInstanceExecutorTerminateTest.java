package mdt.exector.jar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.instance.jar.JarExecutorConfiguration;


/**
 * 인스턴스 프로세스 종료({@link JarInstanceExecutor#terminate})와 남은 프로세스 정리
 * ({@link JarInstanceExecutor#killOrphanProcesses()})를 실제 프로세스로 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
public class JarInstanceExecutorTerminateTest {
	@TempDir File m_workspace;

	@Test
	public void gracefulStopAlsoStopsDescendants() throws Exception {
		Process proc = new ProcessBuilder("sh", "-c", "sleep 60 & wait").start();
		List<ProcessHandle> children = waitForDescendants(proc);

		assertTrue(JarInstanceExecutor.terminate("t", proc.toHandle(), Duration.ofSeconds(5)));
		assertTrue(proc.waitFor(5, TimeUnit.SECONDS));
		children.forEach(c -> assertFalse(c.isAlive(), "descendant still alive: " + c.pid()));
	}

	@Test
	public void processIgnoringSigtermIsKilledAfterTimeout() throws Exception {
		// trap '' TERM: sh와 그 자식(sleep)이 모두 SIGTERM을 무시한다.
		Process proc = new ProcessBuilder("sh", "-c", "trap '' TERM; sleep 60 & wait").start();
		List<ProcessHandle> children = waitForDescendants(proc);

		assertFalse(JarInstanceExecutor.terminate("t", proc.toHandle(), Duration.ofMillis(500)));
		assertTrue(proc.waitFor(5, TimeUnit.SECONDS));
		for ( ProcessHandle c: children ) {
			c.onExit().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void orphanProcessOfThisInstanceIsKilled() throws Exception {
		File instDir = new File(m_workspace, "orph");
		instDir.mkdirs();
		// 명령행에 '--id=orph'가 있는 프로세스('sh -c <script> $0')
		Process proc = new ProcessBuilder("sh", "-c", "sleep 60", "--id=orph").start();
		Files.writeString(new File(instDir, JarInstanceExecutor.PID_FILE_NAME).toPath(), "" + proc.pid());

		assertEquals(1, newExecutor().killOrphanProcesses());
		assertTrue(proc.waitFor(5, TimeUnit.SECONDS));
		assertFalse(new File(instDir, JarInstanceExecutor.PID_FILE_NAME).exists());
	}

	@Test
	public void unrelatedProcessWithReusedPidIsNotKilled() throws Exception {
		File instDir = new File(m_workspace, "other");
		instDir.mkdirs();
		Process proc = new ProcessBuilder("sh", "-c", "sleep 60", "--id=someone-else").start();
		try {
			Files.writeString(new File(instDir, JarInstanceExecutor.PID_FILE_NAME).toPath(), "" + proc.pid());

			assertEquals(0, newExecutor().killOrphanProcesses());
			assertTrue(proc.isAlive());
			assertFalse(new File(instDir, JarInstanceExecutor.PID_FILE_NAME).exists());
		}
		finally {
			proc.destroyForcibly();
		}
	}

	private JarInstanceExecutor newExecutor() {
		JarExecutorConfiguration conf = new JarExecutorConfiguration();
		conf.setStopTimeout(Duration.ofSeconds(2));
		MDTInstanceManagerConfiguration mgrConf = new MDTInstanceManagerConfiguration();
		mgrConf.setInstancesDir(m_workspace);
		return new JarInstanceExecutor(mgrConf, conf);
	}

	private static List<ProcessHandle> waitForDescendants(Process proc) throws InterruptedException {
		for ( int i = 0; i < 50; ++i ) {
			List<ProcessHandle> children = proc.toHandle().descendants().toList();
			if ( !children.isEmpty() ) {
				return children;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("no descendant process started");
	}
}
