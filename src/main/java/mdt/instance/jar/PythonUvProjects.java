package mdt.instance.jar;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Scanner;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.async.command.CommandExecution;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class PythonUvProjects {
	private static final Logger s_logger = LoggerFactory.getLogger(PythonUvProjects.class);
	private static final Duration TIMEOUT_SYNC_UV_PROJECT = Duration.ofSeconds(120);
	
	public static final void main(String... args) throws Exception {
		File prjDir = new File("/home/kwlee/tmp/Welder/uv_projects.txt");
		
		syncProjectsAll(prjDir);
	}
	
	public static void syncProjectsAll(File projectListFile) {
		// 'projectListFile' 파일에서 프로젝트 디렉토리 목록을 읽어와서 각 프로젝트에 대해 syncProject() 메서드를 호출
		// 예시: 각 줄마다 프로젝트 디렉토리 경로가 있는 텍스트 파일을 읽어서 처리
		try ( Scanner scanner = new Scanner(projectListFile) ) {
			while ( scanner.hasNextLine() ) {
				String projectDirPath = scanner.nextLine().trim();
				if ( !projectDirPath.isEmpty() ) {
					File projectDir = new File(projectDirPath);
					if ( !projectDir.isAbsolute() ) {
						projectDir = new  File(projectListFile.getParentFile(), projectDirPath);
					}
					try {
						syncProject(projectDir);
					}
					catch ( Exception e ) {
						s_logger.error("Failed to sync project at {}: {}", projectDir.getAbsolutePath(),
																			e.getMessage());
					}
				}
			}
		}
		catch ( IOException e ) {
			s_logger.error("Failed to read project list file: {}", e.getMessage());
		}
	}
	
	public static void syncProject(File projectDir) throws CancellationException, InterruptedException,
																ExecutionException {
		// 'projectDir' 디렉토리에 'pyproject.toml' 파일이 존재하는지 확인
		File pyprojectFile = new File(projectDir, "pyproject.toml");
		if ( !pyprojectFile.isFile() ) {
			throw new IllegalArgumentException("pyproject.toml file not found in " + projectDir.getAbsolutePath());
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
