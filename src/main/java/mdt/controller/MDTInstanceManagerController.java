package mdt.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import utils.Throwables;
import utils.func.Try;
import utils.http.RESTfulErrorEntity;
import utils.io.FileUtils;
import utils.io.IOUtils;
import utils.io.ZipFile;

import mdt.MDTConfiguration.MDTInstanceManagerConfiguration;
import mdt.client.instance.InstanceDescriptorSerDe;
import mdt.controller.DockerCommandUtils.StandardOutputHandler;
import mdt.instance.AbstractInstance;
import mdt.instance.AbstractInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager;
import mdt.instance.jpa.JpaProcessor;
import mdt.model.InvalidResourceStatusException;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstanceManager;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.persistence.EntityManagerFactory;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
@RestController
@RequestMapping(value={"/instance-manager"})
public class MDTInstanceManagerController implements InitializingBean {
	private final Logger s_logger = LoggerFactory.getLogger(MDTInstanceManagerController.class);
    private static final String DOCKER_FILE = "Dockerfile";
	
	@Autowired AbstractInstanceManager<? extends AbstractInstance> m_instanceManager;
	@Autowired EntityManagerFactory m_emFact;
	@Autowired MDTInstanceManagerConfiguration m_instanceManagerConf;
	private JpaProcessor m_processor;
	@Value("${server.host}")
	private String m_host;
	@Value("${server.port}")
	private int m_port;
	private InstanceDescriptorSerDe m_serde = new InstanceDescriptorSerDe();

	@Override
	public void afterPropertiesSet() throws Exception {
		// JPA 기반 InstanceDescriptorManager를 사용하기 위한 초기화 수행.
		m_processor = new JpaProcessor(m_emFact);
		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager();
    	m_processor.run(instDescMgr, () -> m_instanceManager.initialize(instDescMgr));
    	
    	// 'bundles' 디렉토리가 없는 경우 미리 생성함.
    	if ( m_instanceManager.getBundleHomeDir().exists() ) {
    		FileUtils.createDirectories(m_instanceManager.getBundleHomeDir());
    	}
		
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("{} is ready to serve: {}:{}", getClass().getName(), m_host, m_port);
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
    public ResponseEntity<String> getInstanceAll(@RequestParam(name="filter", required=false) String filter,
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
    					return ResponseEntity.ok("" + instDescMgr.count());
    				default:
    					throw new IllegalArgumentException("unknown Aggregate: " + aggregate); 
    			}
    		}
    		else {
    			matches = instDescMgr.getInstanceDescriptorAll();
    		}
    		
    		return ResponseEntity.ok(m_serde.toJson(matches));
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
    public ResponseEntity<String> getAasDescriptor(@PathVariable("id") String id) throws SerializationException {
    	AssetAdministrationShellDescriptor aasDesc = m_processor.get(m_instanceManager,
									    							() -> m_instanceManager.getInstance(id)
									    													.getAASDescriptor());
    	String json = MDTModelSerDe.getJsonSerializer().write(aasDesc);
    	return ResponseEntity.ok(json);
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
    	@Parameter(name = "id", description = "등록 MDTInstance 식별자"),
    	@Parameter(name = "port", description = "등록될 MDTInstance가 사용할 포트번호. "
    											+ "0보다 작거나 같은 경우는 기본 설정 값을 사용한다."),
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
    @PostMapping({"/instances2"})
    @ResponseStatus(HttpStatus.CREATED)
    public String addInstance(@RequestParam("id") String id,
    							@RequestParam("port") int port,
								@RequestParam(name="jar", required=false) MultipartFile mpfJar,
								@RequestParam(name="initialModel", required=false) MultipartFile mpfModel,
								@RequestParam(name="instanceConf", required=false) MultipartFile mpfConf)
		throws IOException, InterruptedException {
		// TODO: 동시에 동일 식별자에 대한 addInstance가 호출될 수 있기 때문에
    	// 이에 대한 처리가 필요하지만 시간이 부족하여 당분간 무시하기로 한다.
    	// TODO: 동일 instance에 대해 add/get/delete 연산들이 동시에 입력되는 경우
    	// 동시성 제어를 해야 하지만 시간이 부족하여 당분간 무시하도록 한다.
    	// TODO: add/start instance 연산의 경우 수행 소요시간이 장시간이 가능하여
    	// socket timeout이 발생될 가능성이 있다. 이를 효과적으로 처리할 수 있는 방법이 필요하다.
    	
    	// MDTInstance에 필요한 모든 파일을 하나의 bundle directory에 복사한다.
    	File bundleDir = buildBundle(id, mpfJar, mpfModel, mpfConf);
    	
    	// InstanceDescriptor를 생성하여 등록한다.
    	// InstanceDescriptor를 이용하여 MDTInstance를 생성한다.
		InstanceDescriptor instDesc = m_processor.get(m_instanceManager,
													() -> m_instanceManager.addInstance(id, port, bundleDir));

    	// bundle directory는 docker 이미지를 생성하고나서는 필요가 없기 때문에 제거한다.
		// bundle directory는 오류가 발생한 경우 관련 로그 파일이 이 디렉토리에 생성되기 때문에
		// 예외가 발생한 경우 이 디렉토리를 지우면 안되기 때문에 addInstance가 성공한 경우만 수행되어야 한다.
    	Try.accept(bundleDir, FileUtils::deleteDirectory);
    	
		return m_serde.toJson(instDesc);
    }

    @Operation(summary = "MDTInstanceManager에 주어진 MDTInstance 등록정보를 등록시킨다.")
    @Parameters({
    	@Parameter(name = "id", description = "등록 MDTInstance 식별자"),
    	@Parameter(name = "port", description = "등록될 MDTInstance가 사용할 포트번호. "
    											+ "0보다 작거나 같은 경우는 기본 설정 값을 사용한다."),
    	@Parameter(name = "instance",
    			description = "MDTInstance 모델 디렉토리 파일 경로."),
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
							@RequestParam("port") int port,
							@RequestParam(name="bundle", required=true) MultipartFile zipFile)
		throws IOException, InterruptedException {
    	// MDTInstance에 필요한 모든 파일을 하나의 bundle directory에 복사한다.
    	File bundleDir = buildBundle(id, zipFile);
    	
    	// InstanceDescriptor를 생성하여 등록한다.
    	// InstanceDescriptor를 이용하여 MDTInstance를 생성한다.
		InstanceDescriptor instDesc = m_processor.get(m_instanceManager,
													() -> m_instanceManager.addInstance(id, port, bundleDir));

    	// bundle directory는 docker 이미지를 생성하고나서는 필요가 없기 때문에 제거한다.
		// bundle directory는 오류가 발생한 경우 관련 로그 파일이 이 디렉토리에 생성되기 때문에
		// 예외가 발생한 경우 이 디렉토리를 지우면 안되기 때문에 addInstance가 성공한 경우만 수행되어야 한다.
    	Try.accept(bundleDir, FileUtils::deleteDirectory);
    	
		return m_serde.toJson(instDesc);
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
    public void removeInstance(@PathVariable("id") String id) {
		m_processor.run(m_instanceManager, () -> m_instanceManager.removeInstance(id));
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
    public ResponseEntity<String> startInstance(@PathVariable("id") String id) throws InterruptedException {
		AbstractInstance inst = m_processor.get(m_instanceManager, () -> m_instanceManager.getInstance(id));
		inst.startAsync();

		inst = m_processor.get(m_instanceManager, () -> m_instanceManager.getInstance(id));
		return ResponseEntity.ok(m_serde.toJson(inst.getInstanceDescriptor()));
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

    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTInstance가 생성한 output log 내용을 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description = "MDTInstance 식별자")
    })
    @ApiResponses(value = {
        	@ApiResponse(responseCode = "200", description = "성공"),
        	@ApiResponse(responseCode = "404",
    					description = "식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.")
        })
    @GetMapping("/instances/{id}/log")
    public ResponseEntity<?> getOutputLog(@PathVariable("id") String id) throws IOException {
    	AbstractInstance inst = m_processor.get(m_instanceManager, () -> m_instanceManager.getInstance(id));
    	try {
			String log = inst.getOutputLog();
			return ResponseEntity.ok(log);
		}
		catch ( IOException e ) {
			String msg = String.format("Failed to read MDTInstance(%s) log", id);
			return ResponseEntity.internalServerError().body(RESTfulErrorEntity.of(msg, e));
		}
    }
    
    @ExceptionHandler()
    public ResponseEntity<RESTfulErrorEntity> handleException(Exception e) {
    	if ( e instanceof ExecutionException ) {
    		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(RESTfulErrorEntity.of(e));
    	}
    	
		Throwable cause = Throwables.unwrapThrowable(e);

    	if ( cause instanceof ResourceNotFoundException ) {
    		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(RESTfulErrorEntity.of(cause));
    	}
    	else if ( cause instanceof ResourceAlreadyExistsException ) {
    		return ResponseEntity.status(HttpStatus.CONFLICT).body(RESTfulErrorEntity.of(cause));
    	}
    	else if ( cause instanceof InvalidResourceStatusException ) {
    		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(RESTfulErrorEntity.of(cause));
    	}
    	else if ( cause instanceof IllegalArgumentException ) {
    		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(RESTfulErrorEntity.of(cause));
    	}
    	else if ( cause instanceof TimeoutException ) {
    		return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(RESTfulErrorEntity.of(cause));
    	}
    	else {
    		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR) .body(RESTfulErrorEntity.of(cause));
    	}
    }
    
    private File buildBundle(String id, MultipartFile zippedBundle) throws IOException {
    	File bundleDir = new File(m_instanceManager.getBundleHomeDir(), id);
    	if ( m_instanceManager.getBundleHomeDir().exists() ) {
    		FileUtils.createDirectories(m_instanceManager.getBundleHomeDir());
    	}
    	File mgrHomeDir = m_instanceManager.getHomeDir();
    	
		// 동일 이름의 directory가 존재할 수도 있기 때문에 해당 디렉토리가 있으면
		// 먼저 삭제하고, 다시 directory를 생성한다.
		FileUtils.deleteDirectory(bundleDir);
		
		File zippedBundleFile = downloadFile(m_instanceManager.getBundleHomeDir(),
											zippedBundle.getOriginalFilename(), zippedBundle);
		new ZipFile(zippedBundleFile.toPath()).unzip(bundleDir.toPath());
		zippedBundleFile.delete();
		
		// FA3ST jar file이 없는 경우에는 default jar 파일을 사용한다.
		File fa3stJarFile = new File(bundleDir, MDTInstanceManager.FA3ST_JAR_FILE_NAME);
		if ( !fa3stJarFile.exists() ) {
			File defaultJarFile = m_instanceManager.getDefaultMDTInstanceJarFile();
			if ( defaultJarFile == null || !defaultJarFile.exists() ) {
				throw new IllegalStateException("No default MDTInstance jar file exists: path=" + defaultJarFile);
			}
			
			FileUtils.copy(defaultJarFile, fa3stJarFile);
		}
		
		// Global 설정 파일이 없는 경우에는 default 설정 파일을 사용한다.
		File globalConfFile = new File(bundleDir, MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
		File srcGlobalConfFile = new File(mgrHomeDir, MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
		if ( srcGlobalConfFile.exists() && !globalConfFile.exists() ) {
			Files.copy(srcGlobalConfFile.toPath(), globalConfFile.toPath());
		}
		
		// Certificate 파일이 없는 경우에는 default 파일을 사용한다.
		File certFile = new File(bundleDir, MDTInstanceManager.CERT_FILE_NAME);
		File defaultCertFile = new File(mgrHomeDir, MDTInstanceManager.CERT_FILE_NAME);
		if ( defaultCertFile.exists() && !certFile.exists() ) {
			FileUtils.copy(defaultCertFile, certFile);
		}
		
		return bundleDir;
    }

	private File buildBundle(String id, MultipartFile mpfJar, MultipartFile mpfModel, MultipartFile mpfConf)
		throws IOException {
    	File bundleDir = new File(m_instanceManager.getBundleHomeDir(), id);
    	if ( m_instanceManager.getBundleHomeDir().exists() ) {
    		FileUtils.createDirectories(m_instanceManager.getBundleHomeDir());
    	}
    	File mgrHomeDir = m_instanceManager.getHomeDir();
    	
		// 동일 이름의 directory가 존재할 수도 있기 때문에 해당 디렉토리가 있으면
		// 먼저 삭제하고, 다시 directory를 생성한다.
		FileUtils.deleteDirectory(bundleDir);

		try {
			FileUtils.createDirectories(bundleDir);
			
			// AAS 초기 모델 파일과 설정 파일을 생성한 bundle 디렉토리로 download 받는다.
			downloadFile(bundleDir, mpfModel.getOriginalFilename(), mpfModel);
			downloadFile(bundleDir, mpfConf.getOriginalFilename(), mpfConf);
			
			// mpfJar은 null이 될 수 있기 때문에 null이 아닌 경우만 jar 파일을 복사한다.
			// null인 경우에는 default jar를 복사한다.
			if ( mpfJar != null ) {
				downloadFile(bundleDir, MDTInstanceManager.FA3ST_JAR_FILE_NAME, mpfJar);
			}
			else {
				File defaultJarFile = m_instanceManager.getDefaultMDTInstanceJarFile();
				if ( defaultJarFile == null || !defaultJarFile.exists() ) {
					throw new IllegalStateException("No default MDTInstance jar file exists: path=" + defaultJarFile);
				}
				
				FileUtils.copy(defaultJarFile, new File(bundleDir, MDTInstanceManager.FA3ST_JAR_FILE_NAME));
			}

			File srcGlobalConfFile = new File(mgrHomeDir, MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
			if ( srcGlobalConfFile.exists() ) {
				File globalConfFile = new File(bundleDir, MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
				Files.copy(srcGlobalConfFile.toPath(), globalConfFile.toPath());
			}

			File defaultCertFile = new File(mgrHomeDir, MDTInstanceManager.CERT_FILE_NAME);
			if ( defaultCertFile.exists() ) {
				File certFile = new File(bundleDir, MDTInstanceManager.CERT_FILE_NAME);
				FileUtils.copy(defaultCertFile, certFile);
			}

			// Dockerfile 파일을 복사한다.
			File srcDockerfile = new File(mgrHomeDir, DOCKER_FILE);
			File tarDockerfile = new File(bundleDir, DOCKER_FILE);
			FileUtils.copy(srcDockerfile, tarDockerfile);
			
			return bundleDir;
		}
		catch ( IOException e ) {
			Try.run(() -> FileUtils.deleteDirectory(bundleDir));
			throw e;
		}
		catch ( Throwable e ) {
			Try.run(() -> FileUtils.deleteDirectory(bundleDir));
			throw e;
		}
	}
    
    private String buildDockerImage(String id, File bundleDir) {
		// bundleDir에 포함된 데이터를 이용하여 docker image를 생성한다.
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("Building docker image: instance=" + id + ", bundleDir=" + bundleDir);
		}
		StandardOutputHandler outputHandler = new DockerCommandUtils.RedirectOutput(
																				new File(bundleDir, "stdout.log"),
																				new File(bundleDir, "stderr.log"));
		String imageName = DockerCommandUtils.buildDockerImage(id, bundleDir, outputHandler);
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("Done: docker image: repo=" + imageName);
		}
		
		return imageName;
    }
    
    private File downloadFile(File topDir, String fileName, MultipartFile mpf) throws IOException {
		File file = new File(topDir, fileName);
		IOUtils.toFile(mpf.getInputStream(), file);
		return file;
    }
}
