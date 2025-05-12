package mdt.instance.docker;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.ListContainersParam;
import org.mandas.docker.client.DockerClient.ListImagesParam;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.messages.Container;
import org.mandas.docker.client.messages.Image;
import org.mandas.docker.client.messages.RegistryAuth;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import utils.KeyValue;
import utils.Tuple;
import utils.Utilities;
import utils.func.FOption;
import utils.func.Unchecked;
import utils.io.FileUtils;
import utils.stream.FStream;
import utils.stream.KeyValueFStream;

import mdt.model.instance.MDTInstanceManagerException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DockerUtils {
	private static final String LABEL_NAME_MDT_TWIN_ID = "mdt-twin-id";
	private static final ListContainersParam ALL_CONTAINERS = ListContainersParam.allContainers(true);
	
	static {
		Logger logger;
		
		logger = (Logger)LoggerFactory.getLogger(org.mandas.docker.client.LoggingPushHandler.class);
		logger.setLevel(Level.WARN);
		logger = (Logger)LoggerFactory.getLogger(org.mandas.docker.client.LoggingBuildHandler.class);
		logger.setLevel(Level.WARN);
	}
	
	/**
	 * Docker daemon에 연결된 도커 이미지들 중에서 'mdt-twin-id' label이 설정된 이미지들의 리스트를 반환한다.
	 *
	 * @param docker	도커 클라이언트 객체
	 * @return	'mdt-twin-id' label이 설정된 이미지들의 리스트.
	 */
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
	
	/**
	 * 주어진 instance 식별자에 해당하는 도커 이미지를 반환한다.
	 *
	 * @param docker	도커 클라이언트 객체
	 * @param instId	검색 대상 instance 식별	자. 
	 * @return	instance 식별자에 해당하는 도커 이미지. 이미지가 존재하지 않는 경우는 {@link FOption#empty()}.
	 * @throws DockerException		도커 이미지 조회 중 오류가 발생한 경우.
	 * @throws InterruptedException	도커 이미지 조회 중 쓰레드가 인터럽트된 경우.
	 */
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
	
	/**
	 * 주어진 label map에서 주어진 label name에 해당하는 label value를 찾아서 반환한다.
	 * 만일 주어진 label map이 {@code null}인 경우는 {@link FOption#empty()}를 반환한다.
	 * 
	 * @param labels    label map
	 * @param labelName label name
	 * @return label value
	 */
	public static FOption<String> findLabelValue(Map<String,String> labels, String labelName) {
		Preconditions.checkArgument(labelName != null, "label name is null");
		
		if ( labels != null ) {
			return KeyValueFStream.from(labels)
							.filter(kv -> kv.key().equals(labelName))
							.map(KeyValue::value)
							.findFirst();
		}
		else {
			return FOption.empty();
		}
	}
	
	/**
	 * 주어진 docker 이미지에 부여된 label 중에서 'mdt-twin-id' label가 존재하면 해당 label value를 반환하고,
	 * 그렇지 않은 경우는 {@link FOption#empty()}를 반환한다.
	 * 
	 * @param image docker image
	 * @return 'mdt-twin-id'에 해당하는 label이 존재하면 해당 label 값. 그렇지 않은
	 * 경우는 {@link FOption#empty()}.
	 */
	public static FOption<String> getInstanceId(Image image) {
		return findLabelValue(image.labels(), LABEL_NAME_MDT_TWIN_ID);
	}
	
	/**
	 * 주어진 docker 컨테이너에 부여된 label 중에서 'mdt-twin-id' label가 존재하면 해당 label value를
	 * 반환하고, 그렇지 않은 경우는 {@link FOption#empty()}를 반환한다.
	 * 
	 * @param container docker container
	 * @return 'mdt-twin-id'에 해당하는 label이 존재하면 해당 label 값. 그렇지 않은 경우는
	 *         {@link FOption#empty()}.
	 */
	public static FOption<String> getInstanceId(Container container) {
		return findLabelValue(container.labels(), LABEL_NAME_MDT_TWIN_ID);
	}
	
	/**
	 * 주어진 docker 이미지의 'repoTags' 중에서 'repo:tag'를 추출하여 반환한다.
	 * 
	 * @param image docker image
	 * @return 'repo:tag' 형식의 'repo'와 'tag'를 갖는 {@link Tuple}. 'repo'가 없는 경우는
	 *         {@link FOption#empty()}.
	 */
	public static FOption<Tuple<String,String>> parseRepoTag(Image image) {
		return FStream.from(image.repoTags())
						.map(t -> Utilities.splitLast(t, ':', Tuple.of(t, null)))
						.findFirst();
	}
	
	/**
	 * 주어진 label map에서 주어진 label name에 해당하는 label value를 찾아서 존재 여부를 반환한다.
	 * 
	 * @param labels    label map
	 * @param labelName label name
	 * @param value     label value
	 * @return label value가 존재하면 {@code true}, 그렇지 않은 경우는 {@code false}.
	 */
	public static boolean hasLabel(Map<String,String> labels, String name, String value) {
		if ( labels == null ) {
			return false;
		}
		else {
			return KeyValueFStream.from(labels)
							.exists(kv -> kv.key().equals(name) && kv.value().equals(value));
		}
	}
	
	/**
     * 주어진 label map에서 주어진 label name에 해당하는 label가 존재하는지 여부를 반환한다.
     * 
     * @param labels    label map
     * @param labelName label name
     * @return label이 존재하면 {@code true}, 그렇지 않은 경우는 {@code false}.
     */
	public static boolean hasLabel(Map<String,String> labels, String name) {
		if ( labels == null ) {
			return false;
		}
		else {
			return KeyValueFStream.from(labels).exists(kv -> kv.key().equals(name));
		}
	}
}
