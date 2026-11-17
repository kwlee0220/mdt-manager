package mdt.exector.jar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import utils.io.LogTailer;


/**
 * 시작 판정: 성공 마커만 찾고, 무해한 ERROR 줄에는 반응하지 않으며, 감시 대상이 끝나면
 * 제한 시간을 기다리지 않고 멈춘다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SentinelFinderTest {
	@TempDir File m_dir;

	@Test
	public void errorLinesDoNotCountAsFailure() throws Exception {
		File log = new File(m_dir, "output.log");
		Files.writeString(log.toPath(), "INFO booting\nERROR some library complained\n"
										+ JarInstanceExecutor.STARTED_MARKER + " MDTInstance started: https://h:1/api/v3.0\n");
		SentinelFinder finder = new SentinelFinder(List.of(JarInstanceExecutor.STARTED_MARKER), () -> false);
		tail(log, finder, Duration.ofSeconds(5));
		assertEquals(0, finder.getSentinel().key());
		assertFalse(finder.isAborted());
	}

	@Test
	public void deadProcessStopsWaitingBeforeTimeout() throws Exception {
		File log = new File(m_dir, "output.log");
		Files.writeString(log.toPath(), "INFO booting\n");
		Process proc = new ProcessBuilder("sh", "-c", "exit 1").start();
		assertTrue(proc.waitFor(5, TimeUnit.SECONDS));

		SentinelFinder finder = new SentinelFinder(List.of(JarInstanceExecutor.STARTED_MARKER), () -> !proc.isAlive());
		// 제한 시간은 1분이지만 프로세스가 이미 끝났으므로 곧바로 멈춘다.
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> tail(log, finder, Duration.ofMinutes(1)));
		assertNull(finder.getSentinel());
		assertTrue(finder.isAborted());
	}

	private static void tail(File log, SentinelFinder finder, Duration timeout) throws Exception {
		LogTailer tailer = LogTailer.builder()
									.file(log)
									.startAtBeginning(true)
									.sampleInterval(Duration.ofMillis(100))
									.timeout(timeout)
									.listener(finder)
									.build();
		tailer.run();
	}
}
