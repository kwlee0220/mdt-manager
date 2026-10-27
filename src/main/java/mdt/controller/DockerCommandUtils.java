package mdt.controller;

import java.io.File;

import utils.Throwables;
import utils.async.command.CommandExecution;
import utils.async.command.CommandExecution.Output;
import utils.func.Try;

import mdt.model.instance.MDTInstanceManagerException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class DockerCommandUtils {
	private DockerCommandUtils() {
		throw new AssertionError("Should not be called: class=" + getClass().getName());
	}
	
	public static String buildDockerImage(String id, File bundleDir, StandardOutputHandler outputHandler) {
    	String outputRepoName = String.format("mdt-twin-%s", id).toLowerCase();
    	try {
    		String twinIdArg = String.format("twinId=%s", id);
	    	CommandExecution.Builder builder = CommandExecution.builder()
										    					.addCommand("docker", "build",
										    								"-t", outputRepoName,
										    								"--build-arg", twinIdArg,
										    								bundleDir.getAbsolutePath())
										    					.workingDirectory(bundleDir);
	    	if ( outputHandler != null ) {
	    		builder = outputHandler.setup(builder);
	    	}
	    	CommandExecution cmd = builder.build();
	    	cmd.run();
	    	
	    	return outputRepoName;
		}
    	catch ( Exception e ) {
    		Throwable cause = Throwables.unwrapThrowable(e);
    		String msg = String.format("Failed to build a DockerImage: id=%s, bundle=%s, cause=%s",
    									id, bundleDir, cause);
    		throw new MDTInstanceManagerException(msg);
    	}
	}
	
	public static void tagDockerImage(String srcRepoName, String taggedRepoName) {
		try {
			CommandExecution tagExec = CommandExecution.builder()
								    					.addCommand("docker", "tag", srcRepoName, taggedRepoName)
								    					.stdout(Output.discard())
								    					.stderr(Output.discard())
								    					.build();
			tagExec.run();
		}
    	catch ( Exception e ) {
    		Throwable cause = Throwables.unwrapThrowable(e);
    		String msg = String.format("Failed to tag a DockerImage: %s -> %s", srcRepoName, taggedRepoName);
    		throw new MDTInstanceManagerException(msg, cause);
    	}
	}
	
	public static void pushToHarbor(String repoName) {
    	try {
			CommandExecution push = CommandExecution.builder()
							    					.addCommand("docker", "push", repoName)
							    					.stdout(Output.discard())
							    					.stderr(Output.discard())
							    					.build();
			push.run();
		}
    	catch ( Exception e ) {
    		Throwable cause = Throwables.unwrapThrowable(e);
    		String msg = String.format("Failed to push a DockerImage(%s)", repoName, cause);
    		throw new MDTInstanceManagerException(msg);
    	}
	}
	
	public static void removeDockerImage(String imageId) {
		CommandExecution removePrevImage = CommandExecution.builder()
															.addCommand("docker", "rmi", imageId)
															.stdout(Output.discard())
															.stderr(Output.discard())
															.build();
		Try.run(removePrevImage::run);
	}
	
	public static interface StandardOutputHandler {
		public CommandExecution.Builder setup(CommandExecution.Builder cmdBuilder);
	}
	
	public static final class InheritOutput implements StandardOutputHandler {
		@Override
		public CommandExecution.Builder setup(CommandExecution.Builder cmdBuilder) {
			return cmdBuilder.stdout(Output.inherit()).stderr(Output.inherit());
		}
	}
	
	public static final class DiscardOutput implements StandardOutputHandler {
		@Override
		public CommandExecution.Builder setup(CommandExecution.Builder cmdBuilder) {
			return cmdBuilder.stdout(Output.discard()).stderr(Output.discard());
		}
	}
	
	public static final class RedirectOutput implements StandardOutputHandler {
		private final File m_stdoutFile;
		private final File m_stderrFile;
		
		public RedirectOutput(File stdoutFile, File stderrFile) {
			m_stdoutFile = stdoutFile;
			m_stderrFile = stderrFile;
		}
		
		@Override
		public CommandExecution.Builder setup(CommandExecution.Builder cmdBuilder) {
			// 파일이 null인 스트림은 기존 설정을 그대로 둔다.
			if ( m_stdoutFile != null ) {
				cmdBuilder.stdout(Output.file(m_stdoutFile));
			}
			if ( m_stderrFile != null ) {
				cmdBuilder.stderr(Output.file(m_stderrFile));
			}
			return cmdBuilder;
		}
	}
}
