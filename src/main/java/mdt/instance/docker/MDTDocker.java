package mdt.instance.docker;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.ListImagesParam;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.messages.Container;
import org.mandas.docker.client.messages.ContainerConfig;
import org.mandas.docker.client.messages.ContainerCreation;
import org.mandas.docker.client.messages.ContainerInfo;
import org.mandas.docker.client.messages.HostConfig;
import org.mandas.docker.client.messages.HostConfig.Bind;
import org.mandas.docker.client.messages.Image;
import org.mandas.docker.client.messages.PortBinding;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import mdt.model.instance.MDTInstanceManager;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTDocker implements Closeable {
	private final DockerClient m_dockerClient;
	
	public static void main(String... args) throws Exception {
		DockerClient client = new JerseyDockerClientBuilder().uri("http://localhost:2375").build();
		MDTDocker docker = new MDTDocker(client);
		
		JsonMapper mapper = JsonMapper.builder()
										.enable(SerializationFeature.INDENT_OUTPUT)
										.build();
		
//		for ( Image image: docker.getImageAll() ) {
//			System.out.println(image);
//		}
		for ( Container cont: docker.getContainerAll() ) {
			System.out.println(cont);
			docker.removeContainer(cont.id());
		}
		ContainerCreation container = docker.createContainer();
		System.out.println(mapper.writeValueAsString(container));
		
		docker.startContainer(container.id());
		ContainerInfo info = docker.inspectContainer(container.id());
		System.out.println(mapper.writeValueAsString(info));
		
		Map<String, List<PortBinding>> bindings = info.networkSettings().ports();
		List<PortBinding> ports = bindings.get("80/tcp");
		System.out.println(ports.get(0).hostPort());
	}
	
	private MDTDocker(DockerClient docker) {
		m_dockerClient = docker;
	}
	
	@Override
	public void close() throws IOException {
		m_dockerClient.close();
	}
	
	public List<Image> getImageAll() throws DockerException, InterruptedException {
		return m_dockerClient.listImages(ListImagesParam.allImages());
	}
	
	public List<Container> getContainerAll() throws DockerException, InterruptedException {
//		ListContainersParam param = ListContainersParam.withLabel("mdt-instance");
		return m_dockerClient.listContainers();
	}
	
	public ContainerCreation createContainer() throws DockerException, InterruptedException {
		Bind modelBinding = Bind.builder()
								.from("/mnt/c/xxx.json")
								.to("/" + MDTInstanceManager.MODEL_FILE_NAME)
								.readOnly(true)
								.build();

		Map<String,List<PortBinding>> portBindings = Maps.newHashMap();
		List<PortBinding> randomPort = Lists.newArrayList();
		randomPort.add(PortBinding.randomPort("0.0.0.0"));
		portBindings.put("80/tcp", randomPort);
		
		HostConfig hostConf = HostConfig.builder()
										.portBindings(portBindings)
										.binds(modelBinding)
										.build();
		ContainerConfig containerConf = ContainerConfig.builder()
														.hostConfig(hostConf)
														.image("nginx")
														.build();
		return m_dockerClient.createContainer(containerConf, "heater");
	}
	
	public void removeContainer(String id) throws DockerException, InterruptedException {
		m_dockerClient.removeContainer(id);
	}
	
	public void startContainer(String id) throws DockerException, InterruptedException {
		m_dockerClient.startContainer(id);
	}
	
	public ContainerInfo inspectContainer(String id) throws DockerException, InterruptedException {
		
		return m_dockerClient.inspectContainer(id);
	}
	
//	public FOption<Image> getImage(DockerImageId imageId) {
//		String fullName = imageId.getFullName();
//		
//		return FStream.from(getImageAll())
//						.findFirst(img -> FStream.of(img.getRepoTags()).exists(fullName::equals));
//	}
//	
//	public void tagImage(String imageId, String repository, String tag) {
//		m_dockerClient.tagImageCmd(imageId, repository, tag).exec();
//	}
//	
//	public void removeTag(String imageName) {
//		m_dockerClient.removeImageCmd(imageName).exec();
//	}
//	
//	public void pushToHarbor(String path, @Nullable Duration timeout)
//		throws TimeoutException, InterruptedException {
//		PushImageResultCallback cb = m_dockerClient.pushImageCmd(path)
//													.exec(new PushImageResultCallback());
//		if ( timeout != null ) {
//			if ( !cb.awaitCompletion(timeout.getSeconds(), TimeUnit.SECONDS) ) {
//				throw new TimeoutException();
//			}
//		}
//		cb.awaitSuccess();
//	}
//	
//	public CreateContainerResponse createContainer(String path, String name, List<PortBinding> portBindings,
//													List<String> envExprs, List<Bind> binds) {
//		return m_dockerClient.createContainerCmd(path)
//								.withName(name)
//								.withPortBindings(portBindings)
//								.withEnv(envExprs)
//								.withBinds(binds)
//								.exec();
//	}
//	
//	private static final String FA3ST_IMAGE_PATH = "fraunhoferiosb/faaast-service";

	
//	
//	// docker: <port>:<model_path>
//	public String startMdtInstance(MDTInstance instance) {
//		String command = instance.getServiceEndpoint();
//		String[] splits = command.split(":");
//		String modelFilePath = splits[2].trim();
//		
//		PortBinding portBinding = PortBinding.parse(splits[1].trim() +":443");
//		Bind volumeBinding = Bind.parse(modelFilePath + ":/" + MDTInstanceManager.CANONICAL_MODEL_FILE);
//		String envBinding = "faaast.model=/" + MDTInstanceManager.CANONICAL_MODEL_FILE;
//		
//		CreateContainerResponse container = m_dockerClient.createContainerCmd(FA3ST_IMAGE_PATH)
//															.withName(instance.getId())
//															.withPortBindings(portBinding)
//															.withBinds(volumeBinding)
//															.withEnv(envBinding)
//															.exec();
//		m_dockerClient.startContainerCmd(container.getId());
//		
//		return container.getId();
//	}
//	
//	public void stopMdtInstance(MDTInstance instance) {
//		String instId = instance.getId();
//		for ( Container container: m_dockerClient.listContainersCmd().exec() ) {
//			if ( instId.equals(container.getId()) ) {
//				m_dockerClient.stopContainerCmd(container.getId()).exec();
//				break;
//			}
//		}
//	}
//	
//	public void start(String containerId) {
//		m_dockerClient.startContainerCmd(containerId).exec();
//	}
//	
//	public void stop(String containerId) {
//		m_dockerClient.stopContainerCmd(containerId).exec();
//	}
//	
//	public void kill(String containerId) {
//		m_dockerClient.killContainerCmd(containerId).exec();
//	}
}
