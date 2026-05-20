package mdt.instance.jar;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Scanner;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.async.command.CommandExecution;

/**
 * Python {@code uv} 패키지 매니저로 관리되는 프로젝트의 의존성을 동기화하는 유틸리티.
 * <p>
 * 외부 명령 {@code uv sync --no-dev}를 실행하여 {@code pyproject.toml}에 명시된 의존성을 설치한다.
 * 단일 프로젝트는 {@link #syncProject(File)}로, 여러 프로젝트는 디렉터리 경로 목록 파일을 받아
 * 일괄 처리하는 {@link #syncProjectsAll(File)}로 동기화한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class PythonUvProjects {
	private static final Logger s_logger = LoggerFactory.getLogger(PythonUvProjects.class);
	private static final Duration TIMEOUT_SYNC_UV_PROJECT = Duration.ofSeconds(120);

	private PythonUvProjects() {}

	/**
	 * 개발/테스트용 진입점. 하드코딩된 프로젝트 목록 파일을 읽어 일괄 동기화한다.
	 */
	public static void main(String... args) throws Exception {
		File prjListFile = new File("/home/kwlee/tmp/Welder/uv_projects.txt");

		syncProjectsAll(prjListFile);
	}

	/**
	 * 프로젝트 디렉터리 경로 목록 파일을 읽어, 각 프로젝트를 순차적으로 동기화한다.
	 * <p>
	 * 목록 파일은 한 줄당 하나의 프로젝트 디렉터리 경로를 담는다. 빈 줄은 무시되며,
	 * 상대 경로는 목록 파일의 부모 디렉터리를 기준으로 해석된다. 개별 프로젝트 동기화가
	 * 실패해도 다음 프로젝트로 계속 진행하고, 실패는 로그에만 남긴다.
	 * 목록 파일 자체를 읽을 수 없는 경우에도 예외 대신 로그만 남긴다.
	 *
	 * @param projectListFile 프로젝트 경로 목록 파일.
	 */
	public static void syncProjectsAll(File projectListFile) {
		try ( Scanner scanner = new Scanner(projectListFile, StandardCharsets.UTF_8) ) {
			while ( scanner.hasNextLine() ) {
				String projectDirPath = scanner.nextLine().trim();
				if ( !projectDirPath.isEmpty() ) {
					File projectDir = new File(projectDirPath);
					if ( !projectDir.isAbsolute() ) {
						projectDir = new File(projectListFile.getParentFile(), projectDirPath);
					}
					try {
						s_logger.info("Syncing project at {}", projectDir.getAbsolutePath());
						syncProject(projectDir);
					}
					catch ( Exception e ) {
						s_logger.error("Failed to sync project at {}", projectDir.getAbsolutePath(), e);
					}
				}
			}
		}
		catch ( IOException e ) {
			s_logger.error("Failed to read project list file: {}", projectListFile.getAbsolutePath(), e);
		}
	}

	/**
	 * 주어진 프로젝트 디렉터리에서 {@code uv sync --no-dev}를 실행하여 의존성을 동기화한다.
	 * <p>
	 * 디렉터리에 {@code pyproject.toml}이 없으면 {@link IllegalArgumentException}을 던진다.
	 * 외부 명령 실행은 최대 120초의 제한 시간을 가지며, 표준 출력/오류는 현재 프로세스의
	 * 출력으로 그대로 전달된다.
	 *
	 * @param projectDir 대상 프로젝트 디렉터리.
	 * @throws IllegalArgumentException {@code pyproject.toml}이 존재하지 않는 경우.
	 * @throws CancellationException	명령 실행이 취소된 경우.
	 * @throws InterruptedException	명령 실행 대기 중 쓰레드가 인터럽트된 경우.
	 * @throws ExecutionException	명령 실행 중 오류가 발생한 경우.
	 */
	public static void syncProject(File projectDir) throws CancellationException, InterruptedException,
																ExecutionException {
		File pyprojectFile = new File(projectDir, "pyproject.toml");
		if ( !pyprojectFile.isFile() ) {
			throw new IllegalArgumentException("pyproject.toml file not found in "
												+ projectDir.getAbsolutePath());
		}

		CommandExecution exec = CommandExecution.builder()
												.addCommand("uv", "sync", "--no-dev")
												.workingDirectory(projectDir)
												.timeout(TIMEOUT_SYNC_UV_PROJECT)
												.inheritStdout()
												.inheritStderr()
												.build();
		s_logger.info("Syncing uv project in {}", projectDir.getAbsolutePath());
		exec.run();
	}
}
