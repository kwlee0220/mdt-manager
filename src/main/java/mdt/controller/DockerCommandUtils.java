package mdt.controller;

import java.io.File;

import lombok.experimental.UtilityClass;

import utils.Throwables;
import utils.async.command.CommandExecution;
import utils.func.FOption;
import utils.func.Try;

import mdt.model.instance.MDTInstanceManagerException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@UtilityClass
public class DockerCommandUtils {
	public static String buildDockerImage(String id, File bundleDir, StandardOutputHandler outputHandler) {
    	String outputRepoName = String.format("mdt-twin-%s", id).toLowerCase();
    	try {
    		String twinIdArg = String.format("twinId=%s", id);
	    	CommandExecution.Builder builder = CommandExecution.builder()
										    					.addCommand("docker", "build",
										    								"-t", outputRepoName,
										    								"--build-arg", twinIdArg,
										    								bundleDir.getAbsolutePath())
										    					.setWorkingDirectory(bundleDir);
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
								    					.discardStdout()
								    					.discardStderr()
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
							    					.discardStdout()
							    					.discardStderr()
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
															.discardStdout()
															.discardStderr()
															.build();
		Try.run(removePrevImage::run);
	}
	
	public static interface StandardOutputHandler {
		public CommandExecution.Builder setup(CommandExecution.Builder cmdBuilder);
	}
	
	public static final class InheritOutput implements StandardOutputHandler {
		@Override
		public CommandExecution.Builder setup(CommandExecution.Builder cmdBuilder) {
			return cmdBuilder.inheritStdout().inheritStdout();
		}
	}
	
	public static final class DiscardOutput implements StandardOutputHandler {
		@Override
		public CommandExecution.Builder setup(CommandExecution.Builder cmdBuilder) {
			return cmdBuilder.discardStdin().discardStdout();
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
			cmdBuilder = FOption.map(m_stdoutFile, cmdBuilder::redirectStdoutToFile);
			return FOption.map(m_stderrFile, cmdBuilder::redirectStderrToFile);
		}
	}
}
