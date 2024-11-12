package mdt.instance.docker;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.ListContainersParam;
import org.mandas.docker.client.DockerClient.ListImagesParam;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.messages.Container;
import org.mandas.docker.client.messages.Image;
import org.mandas.docker.client.messages.RegistryAuth;
import org.slf4j.LoggerFactory;

import utils.KeyValue;
import utils.Utilities;
import utils.func.FOption;
import utils.func.Tuple;
import utils.func.Unchecked;
import utils.io.FileUtils;
import utils.stream.FStream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import mdt.model.instance.MDTInstanceManagerException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DockerUtils {
	private static final org.slf4j.Logger s_logger = LoggerFactory.getLogger(DockerUtils.class);
	
	private static final String LABEL_NAME_MDT_TWIN_ID = "mdt-twin-id";
	private static final ListContainersParam ALL_CONTAINERS = ListContainersParam.allContainers(true);
	
	static {
		Logger logger;
		
		logger = (Logger)LoggerFactory.getLogger(org.mandas.docker.client.LoggingPushHandler.class);
		logger.setLevel(Level.WARN);
		logger = (Logger)LoggerFactory.getLogger(org.mandas.docker.client.LoggingBuildHandler.class);
		logger.setLevel(Level.WARN);
	}
	
	public static List<Image> getInstanceImageAll(DockerClient docker) {
		try {
			return FStream.from(docker.listImages(ListImagesParam.allImages()))
							.filter(img -> hasLabel(img.labels(), "mdt-twin-id"))
							.toList();
		}
		catch ( Exception e ) {
			throw new MDTInstanceManagerException("Failed to get InstanceImage all", e);
		}
	}
	
	public static FOption<Image> getInstanceImage(DockerClient docker, String instId)
		throws DockerException, InterruptedException {
		return FStream.from(docker.listImages(ListImagesParam.allImages()))
						.filter(img -> DockerUtils.hasLabel(img.labels(), "mdt-twin-id", instId))
						.filter(img -> DockerUtils.parseRepoTag(img).isPresent())
						.findFirst();
	}
	
	public static String tagImageForHarbor(DockerClient docker, Image image,
											HarborConfiguration harborConf, String tag)
		throws DockerException, InterruptedException {
		String srcRepoName = parseRepoTag(image).get()._1;
    	String taggedRepo = String.format("%s/%s/%s:%s",
    										harborConf.getHost(), harborConf.getProject(), srcRepoName, tag);
    	docker.tag(image.id(), taggedRepo);
    	return taggedRepo;
	}
	
	public static String buildInstanceImage(DockerClient docker, String instId, File bundleDir)
		throws DockerException, InterruptedException, IOException {
		Map<String,String> dictionary = Map.of("twinId", instId);
		
		// Dockerfile 내용 중에 variable이 존재하는 경우 substitute 시킨다.
		// 'mdt-twin-id' label의 값으로 instance id를 설정한다.
		File dockerFile = FileUtils.path(bundleDir, "Dockerfile");
		Utilities.substributeFile(dockerFile, dictionary);

    	String outputImageRepo = String.format("mdt-twin-%s", instId).toLowerCase();
    	// 왜 그런지 잘 모르겠으나 BuildParam을 사용하면 제대로 동작하는 것 같지 않음
//		m_dockerClient.build(bundleDir.toPath(), outputImageId, BuildParam.quiet());
    	docker.build(bundleDir.toPath(), outputImageRepo);
		
		return outputImageRepo;
	}
	
	public static void pushImage(DockerClient docker, String imageId, HarborConfiguration harborConf)
		throws DockerException, InterruptedException {
		docker.push(imageId, RegistryAuth.builder()
										.username(harborConf.getUser())
										.password(harborConf.getPassword())
										.build());
	}
	
	public static void removeInstanceImage(DockerClient docker, String instId)
		throws DockerException, InterruptedException {
		getInstanceImage(docker, instId)
			.ifPresent(img -> {
				Unchecked.runOrThrowSneakily(() -> docker.removeImage(img.id(), true, false));
			});
	}
	
	//
	//	docker containers
	//
	
	public static List<Container> getInstanceContainerAll(DockerClient docker) throws DockerException, InterruptedException {
		return FStream.from(docker.listContainers(ALL_CONTAINERS))
						.filter(cont -> getInstanceId(cont).isPresent())
						.toList();
	}
	
	//
	// Utility methods
	//
	
	public static FOption<String> findLabelValue(Map<String,String> labels, String labelName) {
		if ( labels != null ) {
			return FStream.from(labels)
							.filter(kv -> kv.key().equals(labelName))
							.map(KeyValue::value)
							.findFirst();
		}
		else {
			return FOption.empty();
		}
	}
	
	public static FOption<String> getInstanceId(Image image) {
		return findLabelValue(image.labels(), LABEL_NAME_MDT_TWIN_ID);
	}
	
	public static FOption<String> getInstanceId(Container container) {
		return findLabelValue(container.labels(), LABEL_NAME_MDT_TWIN_ID);
	}
	
	public static FOption<Tuple<String,String>> parseRepoTag(Image image) {
		return FStream.from(image.repoTags())
						.map(t -> Utilities.splitLast(t, ':', Tuple.of(t, null)))
						.findFirst();
	}
	
	public static boolean hasLabel(Map<String,String> labels, String name, String value) {
		if ( labels == null ) {
			return false;
		}
		else {
			return FStream.from(labels)
							.exists(kv -> kv.key().equals(name) && kv.value().equals(value));
		}
	}
	public static boolean hasLabel(Map<String,String> labels, String name) {
		if ( labels == null ) {
			return false;
		}
		else {
			return FStream.from(labels).exists(kv -> kv.key().equals(name));
		}
	}
	
	
	public static void main(String... args) throws Exception {
		DockerClient docker = new JerseyDockerClientBuilder().uri("http://localhost:2375").build();
		HarborConfiguration harbor = new HarborConfiguration();
		harbor.setUser("etri");
		harbor.setPassword("zento");
	}
}
