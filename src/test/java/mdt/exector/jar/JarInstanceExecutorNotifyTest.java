package mdt.exector.jar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.instance.jar.JarExecutionArguments;
import mdt.instance.jar.JarExecutorConfiguration;
import mdt.model.instance.MDTInstanceStatus;


/**
 * 상태 통지가 실행기의 락을 쥔 채 리스너를 기다리지 않는지 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JarInstanceExecutorNotifyTest {
	@TempDir File m_workspace;

	@Test
	public void slowListenerDoesNotBlockTheExecutor() throws Exception {
		JarExecutorConfiguration conf = new JarExecutorConfiguration();
		conf.setStartTimeout(Duration.ofSeconds(20));
		conf.setSampleInterval(Duration.ofMillis(100));
		MDTInstanceManagerConfiguration mgrConf = new MDTInstanceManagerConfiguration();
		mgrConf.setMdtUrl("http://localhost:12985");
		mgrConf.setInstancesDir(m_workspace);
		JarInstanceExecutor executor = new JarInstanceExecutor(mgrConf, conf);

		CountDownLatch release = new CountDownLatch(1);
		List<MDTInstanceStatus> seen = new CopyOnWriteArrayList<>();
		executor.addExecutionListener(new JarExecutionListener() {
			@Override
			public void statusChanged(String id, MDTInstanceStatus status, String endpoint) {
				seen.add(status);
				try {
					release.await(10, TimeUnit.SECONDS);		// DB가 느린 리스너
				}
				catch ( InterruptedException e ) { }
				throw new IllegalStateException("listener failure must not break the executor");
			}
			@Override
			public void timeoutExpired() { }
		});

		new File(m_workspace, "t1").mkdirs();
		JarExecutionArguments args = new JarExecutionArguments();
		args.setJarFile(new File(m_workspace, "no-such.jar").getAbsolutePath());	// java가 곧바로 실패한다
		Thread starter = new Thread(() -> {
			try {
				executor.start("t1", "aas-t1", args);
			}
			catch ( Exception ignored ) { }
		});
		starter.start();

		// 리스너가 막혀 있어도 실행기 조회는 곧바로 돌아온다.
		assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
			while ( seen.isEmpty() ) {
				Thread.sleep(20);
			}
			executor.getStatus("t1");
		});

		release.countDown();
		starter.join(TimeUnit.SECONDS.toMillis(30));
		executor.shutdown();
		assertEquals(MDTInstanceStatus.STARTING, seen.get(0));
		assertTrue(seen.contains(MDTInstanceStatus.FAILED), "" + seen);
	}
}
