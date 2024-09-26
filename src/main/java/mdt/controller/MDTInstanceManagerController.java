package mdt.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.google.common.base.Preconditions;

import utils.func.FOption;
import utils.func.Try;
import utils.io.IOUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.persistence.EntityManagerFactory;
import mdt.MDTController;
import mdt.client.instance.InstanceDescriptorSerDe;
import mdt.instance.AbstractInstance;
import mdt.instance.AbstractInstanceManager;
import mdt.instance.docker.DockerInstance;
import mdt.instance.docker.DockerInstanceManager;
import mdt.instance.jar.JarInstance;
import mdt.instance.jar.JarInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager;
import mdt.instance.jpa.JpaProcessor;
import mdt.instance.k8s.KubernetesInstance;
import mdt.instance.k8s.KubernetesInstanceManager;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.DockerExecutionArguments;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.JarExecutionArguments;
import mdt.model.instance.KubernetesExecutionArguments;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
@RestController
@RequestMapping("/instance-manager")
public class MDTInstanceManagerController extends MDTController<MDTInstance> implements InitializingBean {
	private final Logger s_logger = LoggerFactory.getLogger(MDTInstanceManagerController.class);
	
	@Autowired AbstractInstanceManager<? extends AbstractInstance> m_instanceManager;
	@Autowired EntityManagerFactory m_emFact;
	private JpaProcessor m_processor;
	@Value("file:${instanceManager.instancesDir}")
	private File m_instancesDir;
	private InstanceDescriptorSerDe m_serde = new InstanceDescriptorSerDe();

	@Override
	public void afterPropertiesSet() throws Exception {
		Files.createDirectories(m_instancesDir.toPath());
		
		// InstanceManager가 다시 시작할 때는, 전에 등록된 모든 MDTInstance의 status/serviceEndpoint 값을
		// 초기화 시킨다.
		m_processor = new JpaProcessor(m_emFact);
		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager();
		m_processor.run(instDescMgr, () -> {
			for ( JpaInstanceDescriptor instDesc: instDescMgr.getInstanceDescriptorAll() ) {
				instDesc.setBaseEndpoint(null);
				instDesc.setStatus(MDTInstanceStatus.STOPPED);
			}
		});
		
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("{} is ready to serve: instances-dir={}", getClass().getName(), m_instancesDir);
		}
	}

    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTInstance 등록정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description = "검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = InstanceDescriptor.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404", description = "식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.")
    })
    @GetMapping("/instances/{id}")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<String> getInstance(@PathVariable("id") String id) {
		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager();
    	JpaInstanceDescriptor desc = m_processor.get(instDescMgr, () -> instDescMgr.getInstanceDescriptor(id));
    	
    	if ( desc != null ) {
    		return ResponseEntity.ok(m_serde.toJson(desc));
    	}
    	else {
    		throw new ResourceNotFoundException("MDTInstance", "id=" + id);
    	}
    }
    
    @Operation(summary = "MDTInstanceManager에 등록된 모든 MDTInstance 등록정보들을 반환한다.")
    @Parameters({
    	@Parameter(name = "filter", description = "검색 필터 표현식.", example = "idShort like \"abc%\""),
    	@Parameter(name = "aggregate", description = "사용할 집계함수명", example = "count")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
    		content = {
    			@Content(mediaType = "application/json",
    					array = @ArraySchema(schema=@Schema(implementation = InstanceDescriptor.class)))
    		}
    	)
    })
    @GetMapping("/instances")
    @ResponseStatus(HttpStatus.OK)
    public String getInstanceAll(@RequestParam(name="filter", required=false) String filter,
    								@RequestParam(name="aggregate", required=false) String aggregate) {
		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager();
    	return m_processor.get(instDescMgr, () -> {
        	List<JpaInstanceDescriptor> matches;
    		if ( filter != null ) {
    			matches = instDescMgr.findInstanceDescriptorAll(filter);
    		}
    		else if ( aggregate != null ) {
    			switch ( aggregate.toLowerCase() ) {
    				case "count":
    					return "" + instDescMgr.count();
    				default:
    					throw new IllegalArgumentException("unknown Aggregate: " + aggregate); 
    			}
    		}
    		else {
    			matches = instDescMgr.getInstanceDescriptorAll();
    		}
    		return m_serde.toJson(matches);
    	});
    }

    @Operation(summary = "MDTInstance에 포함된 AssetAdministrationShell 모델 기술자를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description = "대상 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = InstanceDescriptor.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404", description = "식별자에 해당하는 MDTInstance가 등록되지 않은 경우.")
    })
    @GetMapping({"instances/{id}/aas_descriptor"})
    @ResponseStatus(HttpStatus.OK)
    public AssetAdministrationShellDescriptor getAasDescriptor(@PathVariable("id") String id)
    	throws SerializationException {
    	return m_processor.get(m_instanceManager,
    							() -> m_instanceManager.getInstance(id)
    													.getAASDescriptor());
    }

    @Operation(summary = "MDTInstance에 포함된 모든 Submodel 기술자들을 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description = "대상 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = InstanceDescriptor.class), mediaType = "application/json")
			})
    })
    @GetMapping({"instances/{id}/submodel_descriptors"})
    @ResponseStatus(HttpStatus.OK)
    public List<SubmodelDescriptor> getSubmodelDescriptors(@PathVariable("id") String id)
    	throws SerializationException {
    	return m_processor.get(m_instanceManager,
    							() -> m_instanceManager.getInstance(id)
    													.getAllSubmodelDescriptors());
    }

    @Operation(summary = "MDTInstanceManager에 주어진 MDTInstance 등록정보를 등록시킨다.")
    @Parameters({
    	@Parameter(name = "jar",
    			description = "(Jar기반 MDTInstance의 경우) MDTInstance 구현 Jar 파일 경로."),
    	@Parameter(name = "imageId",
    			description = "(Docker/Kubernetes기반 MDTInstance의 경우) MDTInstance 구현 docker 이미지 이름"),
    	@Parameter(name = "initialModel", description = "등록시킬 초기 AAS 모델 파일"),
    	@Parameter(name = "instanceConf", description = "등록시킬 MDTInstance의 설정 파일"),
    })
    @ApiResponses(value = {
        	@ApiResponse(responseCode = "200", description = "성공",
    			content = {
    				@Content(schema = @Schema(implementation = InstanceDescriptor.class),
    						mediaType = "application/json")
    			}),
        	@ApiResponse(responseCode = "400",
        				description = "식별자에 해당하는 MDTInstance가 이미 등록되어 있습니다.")
        })
    @PostMapping({"/instances"})
    @ResponseStatus(HttpStatus.CREATED)
    public String addInstance(@RequestParam("id") String id,
								@RequestParam(name="jar", required=false) MultipartFile mpfJar,
								@RequestParam(name="imageId", required=false) String imageId,
								@RequestParam(name="initialModel", required=false) MultipartFile mpfModel,
								@RequestParam(name="instanceConf", required=false) MultipartFile mpfConf) {
    	AbstractInstance inst = null;
    	if ( m_instanceManager instanceof JarInstanceManager ) {
    		inst = addJarInstance(id, mpfJar, mpfModel, mpfConf);
    	}
    	else if ( m_instanceManager instanceof DockerInstanceManager ) {
        	inst = addDockerInstance(id, imageId, mpfModel, mpfConf);
    	}
    	else if ( m_instanceManager instanceof KubernetesInstanceManager ) {
        	Preconditions.checkNotNull(imageId, "ImageId was null");
        	inst = addKubernetesInstance(id, imageId, mpfModel);
    	}
    	else {
			throw new IllegalArgumentException("Unsupported InstanceManager type: " + m_instanceManager);
    	}
    	
    	return m_serde.toJson(inst.getInstanceDescriptor());
    }

    private JarInstance addJarInstance(String id, MultipartFile mpfJar, MultipartFile mpfModel,
    									MultipartFile mpfConf) {
    	JarInstanceManager instMgr = (JarInstanceManager)m_instanceManager;
    	
    	File instDir = instMgr.getInstanceHomeDir(id);
    	File tempInstDir = new File(instDir.getParentFile(), instDir.getName() + "_");

    	try {
    		Files.createDirectories(tempInstDir.toPath());

			// AAS 초기 모델 파일과 설정 파일을 download 받는다.
			File modelFile = downloadFile(tempInstDir, MDTInstanceManager.MODEL_FILE_NAME, mpfModel);
			File confFile = downloadFile(tempInstDir, MDTInstanceManager.CONF_FILE_NAME, mpfConf);
			
			// mpfJar은 null이 될 수 있기 때문에 null이 아닌 경우만 jar 파일을 복사한다.
			String jarFilePath = null;
			if ( mpfJar != null ) {
				jarFilePath = downloadFile(tempInstDir,
										MDTInstanceManager.FA3ST_JAR_FILE_NAME,
										mpfJar)
									.getAbsolutePath();
			}
			JarExecutionArguments args = JarExecutionArguments.builder()
															.jarFile(jarFilePath)
															.modelFile(modelFile.getAbsolutePath())
															.configFile(confFile.getAbsolutePath())
															.build();
			String argsJson = instMgr.toExecutionArgumentsString(args);
			
			return m_processor.get(instMgr, () -> (JarInstance)instMgr.addInstance(id, modelFile, argsJson));
		}
		catch ( Exception e ) {
			// InstanceDescriptor가 추가된 경우라도 transaction rollback으로 인해
			// 자동적으로 제거되기 때문에 별도의 remove 작업이 필요없다.
			throw new MDTInstanceManagerException("" + e);
		}
    	finally {
    		try {
    			FileUtils.deleteDirectory(tempInstDir);
    		}
    		catch ( IOException e ) {
    			s_logger.warn("Failed to temporary MDT-Instance directory: path=" + tempInstDir
    						+ ", cause=" + e);
    		}
    	}
    }

    private DockerInstance addDockerInstance(String id, String imageId, MultipartFile mpfModel,
    											MultipartFile mpfConf) {
    	DockerInstanceManager instMgr = (DockerInstanceManager)m_instanceManager;
    	
    	File instDir = instMgr.getInstanceHomeDir(id);
    	try {
			Files.createDirectories(instDir.toPath());
			
			// Docker image-id가 설정됮 않은 경우에는 default 값을 사용한다.
			imageId = FOption.getOrElse(imageId, instMgr.getDefaultDockerImageId());

			// AAS 초기 모델 파일과 설정 파일을 download 받는다.
			File modelFile = downloadFile(instDir, MDTInstanceManager.MODEL_FILE_NAME, mpfModel);
			File confFile = downloadFile(instDir, MDTInstanceManager.CONF_FILE_NAME, mpfConf);
			
			DockerExecutionArguments args = DockerExecutionArguments.builder()
																.imageId(imageId)
																.modelFile(modelFile.getAbsolutePath())
																.configFile(confFile.getAbsolutePath())
																.build();
			String argsJson = instMgr.toExecutionArgumentsString(args);
			
			return m_processor.get(instMgr, () -> (DockerInstance)instMgr.addInstance(id, modelFile, argsJson));
		}
		catch ( IOException e ) {
			throw new MDTInstanceManagerException("" + e);
		}
    }

    private KubernetesInstance addKubernetesInstance(String id, String imageId,  MultipartFile mpfModel) {
    	KubernetesInstanceManager instMgr = (KubernetesInstanceManager)m_instanceManager;

    	File instDir = instMgr.getInstanceHomeDir(id);
    	try {
			Files.createDirectories(instDir.toPath());

			// AAS 초기 모델 파일과 설정 파일을 download 받는다.
			File modelFile = downloadFile(instDir, MDTInstanceManager.MODEL_FILE_NAME, mpfModel);

			KubernetesExecutionArguments args = KubernetesExecutionArguments.builder()
																			.imageId(imageId)
																			.build();
			String argsJson = instMgr.toExecutionArgumentsString(args);
			
			return m_processor.get(instMgr, () -> (KubernetesInstance)instMgr.addInstance(id, modelFile, argsJson));
		}
		catch ( IOException e ) {
			throw new MDTInstanceManagerException("" + e);
		}
    }

    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTInstance 등록정보를 삭제한다.")
    @Parameters({
    	@Parameter(name = "id", description = "검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "204", description = "성공"),
    	@ApiResponse(responseCode = "404", description = "식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.")
    })
    @DeleteMapping("/instances/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeInstance(@PathVariable("id") String id) throws SerializationException {
		try {
			m_processor.run(m_instanceManager, () -> m_instanceManager.removeInstance(id));
		}
		finally {
	    	File topDir = new File(m_instancesDir, id);
	    	FileSystemUtils.deleteRecursively(topDir);
		}
    }

    @Operation(summary = "MDTInstanceManager에 등록된 모든 MDTInstance 등록정보를 삭제한다.")
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "204", description = "성공"),
    })
    @DeleteMapping("/instances")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeInstanceAll() throws SerializationException {
    	Try.run(() -> m_processor.run(m_instanceManager, () -> m_instanceManager.removeAllInstances()));
    }

    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTInstance를 시작시킨다.")
    @Parameters({
    	@Parameter(name = "id", description = "시작시킬 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공"),
    	@ApiResponse(responseCode = "404",
					description = "식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다."),
    	@ApiResponse(responseCode = "400",
    				description = "식별자에 해당하는 MDTInstance가 이미 실행 중인 경우.")
    })
    @PutMapping("/instances/{id}/start")
    @ResponseStatus(HttpStatus.OK)
    public String startInstance(@PathVariable("id") String id) {
    	AbstractInstance inst = m_processor.get(m_instanceManager, () -> m_instanceManager.getInstance(id));
    	inst.startAsync();

    	inst = m_processor.get(m_instanceManager, () -> m_instanceManager.getInstance(id));
    	return m_serde.toJson(inst.getInstanceDescriptor());
    }

    @Operation(
    	summary = "MDTInstance 식별자에 해당하는 MDTInstance를 중지시킨다.",
    	description = "메소드는 MDTInstance의 중지 요청을 수행하고, 완전히 중지되기 전에 반환될 수도 있다. "
    )
    @Parameters({
    	@Parameter(name = "id", description = "중지시킬 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공"),
    	@ApiResponse(responseCode = "404",
					description = "식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다."),
    	@ApiResponse(responseCode = "400",
    				description = "식별자에 해당하는 MDTInstance가 실행 중이지 않은 경우.")
    })
    @PutMapping("/instances/{id}/stop")
    @ResponseStatus(HttpStatus.OK)
    public String stopInstance(@PathVariable("id") String id) {
    	AbstractInstance inst = m_processor.get(m_instanceManager, () -> m_instanceManager.getInstance(id));
    	inst.stopAsync();

    	inst = m_processor.get(m_instanceManager, () -> m_instanceManager.getInstance(id));
    	return m_serde.toJson(inst.getInstanceDescriptor());
    }
    
    private File downloadFile(File topDir, String fileName, MultipartFile mpf) throws IOException {
		File file = new File(topDir, fileName);
		IOUtils.toFile(mpf.getInputStream(), file);
		return file;
    }
}
