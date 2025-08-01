package mdt.controller;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import utils.KeyValue;
import utils.Throwables;
import utils.func.Try;
import utils.http.RESTfulErrorEntity;
import utils.io.FileUtils;
import utils.io.IOUtils;
import utils.io.ZipFile;
import utils.jpa.JpaSession;
import utils.stream.FStream;

import mdt.Globals;
import mdt.client.instance.InstanceDescriptorSerDe;
import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.instance.external.ExternalInstance;
import mdt.instance.external.ExternalInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AASUtils;
import mdt.model.InvalidResourceStatusException;
import mdt.model.MDTModelSerDe;
import mdt.model.ModelValidationException;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;
import mdt.model.expr.LiteralExpr;
import mdt.model.expr.MDTElementReferenceExpr;
import mdt.model.expr.MDTExpr;
import mdt.model.expr.MDTExprParser;
import mdt.model.expr.MDTSubmodelExpr;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTModel;
import mdt.model.instance.MDTModelService;
import mdt.model.sm.ref.DefaultSubmodelReference;
import mdt.model.sm.ref.ElementReference;
import mdt.model.sm.ref.ElementReferences;
import mdt.model.sm.ref.MDTElementReference;
import mdt.model.sm.ref.SubmodelBasedElementReference;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.model.sm.variable.AbstractVariable.ReferenceVariable;
import mdt.model.sm.variable.Variable;
import mdt.model.sm.variable.Variables;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
@RestController
@RequestMapping(value={"/instance-manager"})
public class MDTInstanceManagerController implements InitializingBean {
	private final Logger s_logger = LoggerFactory.getLogger(MDTInstanceManagerController.class);
    @SuppressWarnings("unused")
	private static final String DOCKER_FILE = "Dockerfile";
	
	@Autowired AbstractJpaInstanceManager<? extends JpaInstance> m_instanceManager;
	@Value("${server.host}")
	private String m_host;
	@Value("${server.port}")
	private int m_port;
	private InstanceDescriptorSerDe m_serde = new InstanceDescriptorSerDe();

	@Override
	public void afterPropertiesSet() throws Exception {
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
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
        	JpaInstanceDescriptor desc = m_instanceManager.getInstanceDescriptor(id);
    		return ResponseEntity.ok(m_serde.toJson(desc));
    	}
    }

    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTModel를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description = "검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = MDTModel.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404", description = "식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.")
    })
    @GetMapping("/instances/{id}/$mdt-model")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> getMDTModel(@PathVariable("id") String id) {
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
    		JpaInstanceDescriptor instDesc = m_instanceManager.getInstanceDescriptor(id);
    		MDTModelService mdtModelSvc = MDTModelService.of(m_instanceManager, instDesc);
    		
    		MDTModel mdtModel = mdtModelSvc.readModel();
    		return ResponseEntity.ok(mdtModel);
    	}
		catch ( IOException e ) {
			return ResponseEntity.internalServerError().body(RESTfulErrorEntity.of(e));
		}
    }
    
    @Operation(summary = "MDTInstanceManager에 등록된 모든 MDTInstance 등록정보들을 반환한다.")
    @Parameters({
    	@Parameter(name = "filter", description = "검색 필터 표현식.", example = "idShort like \"abc%\"")
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
    public ResponseEntity<String> getInstanceAll(@RequestParam(name="filter", required=false) String filter) {
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
	    	List<? extends JpaInstance> matches = ( filter != null )
						    					? m_instanceManager.getInstanceAllByFilter(filter)
						    					: m_instanceManager.getInstanceAll();
			List<InstanceDescriptor> descList = FStream.from(matches)
														.map(MDTInstance::getInstanceDescriptor)
														.toList();
			return ResponseEntity.ok(m_serde.toJson(descList));
    	}
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
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
    		return m_instanceManager.getInstance(id).getAASDescriptor();
    	}
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
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
    		return m_instanceManager.getInstance(id).getSubmodelDescriptorAll();
    	}
    }

    @Operation(summary = "MDTInstanceManager에 주어진 MDTInstance 등록정보를 등록시킨다.")
    @Parameters({
    	@Parameter(name = "id", description = "등록 MDTInstance 식별자"),
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
								@RequestParam(name="bundle", required=true) MultipartFile zipFile)
		throws IOException, ModelValidationException, MDTInstanceManagerException {
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADDING(id));
			
	    	// 입력 받은 zip 파일을 풀어서 bundle directory를 생성한다.
	    	File bundleDir = buildBundle(id, zipFile);
	    	try {
		    	// Bundle directory의 내용을 이용해서 InstanceDescriptor를 생성하여 등록하고,
	    		// 이를 통해 MDTInstance를 생성한다.
	    		MDTInstance inst = m_instanceManager.addInstance(id, bundleDir);
				String descJson = m_serde.toJson(inst.getInstanceDescriptor());
				Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADDED(id));
	
				return descJson;
	    	}
			catch ( IOException | ModelValidationException | MDTInstanceManagerException e ) {
				Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADD_FAILED(id));
				throw e;
			}
	    	catch ( Throwable e ) {
				Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADD_FAILED(id));
				Throwable cause = Throwables.unwrapThrowable(e);
				throw new MDTInstanceManagerException("failed to add MDTInstance: id=" + id, cause);
	    	}
	    	finally {
		    	// bundle directory는 docker 이미지를 생성하고나서는 필요가 없기 때문에 제거한다.
		    	Try.accept(bundleDir, FileUtils::deleteDirectory);
	    	}
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
    public ResponseEntity<?> removeInstance(@PathVariable("id") String id) {
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
    		m_instanceManager.removeInstance(id);
    		return ResponseEntity.noContent().build();
    	}
		catch ( ResourceNotFoundException e ) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(RESTfulErrorEntity.of(e));
		}
    	catch ( InvalidResourceStatusException e ) {
    		return ResponseEntity.badRequest().body(RESTfulErrorEntity.of(e));
    	}
    }

    @Operation(summary = "MDTInstanceManager에 등록된 모든 MDTInstance 등록정보를 삭제한다.")
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "204", description = "성공"),
    })
    @DeleteMapping("/instances")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeInstanceAll() {
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
    		m_instanceManager.removeInstanceAll();
    	}
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
    public ResponseEntity<?> startInstance(@PathVariable("id") String id) throws InterruptedException {
    	JpaInstance inst;
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
	    	inst = m_instanceManager.getInstance(id);
    	}
    	
		try {
			Duration pollInterval = null;
			Duration timeout = null;
			
			inst.start(pollInterval, timeout);
		}
		catch ( Exception e ) {
			return ResponseEntity.internalServerError().body(RESTfulErrorEntity.of(e));
		}

    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
			InstanceDescriptor desc = m_instanceManager.getInstanceDescriptor(id);
			return ResponseEntity.ok(m_serde.toJson(desc));
    	}
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
    public ResponseEntity<?> stopInstance(@PathVariable("id") String id) {
    	JpaInstance inst;
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
	    	inst = m_instanceManager.getInstance(id);
    	}
    	
    	try {
			inst.stop(null, null);
		}
		catch ( Exception e ) {
			return ResponseEntity.internalServerError().body(RESTfulErrorEntity.of(e));
		}
    	
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
			InstanceDescriptor desc = m_instanceManager.getInstanceDescriptor(id);
			return ResponseEntity.ok(m_serde.toJson(desc));
    	}
    }

    @Operation(summary = "MDTInstance을 등록시킨다.")
    @Parameters({
    	@Parameter(name = "id", description = "등록시킬 MDTInstance 식별자")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	description = "등록시킬 MDTInstance의 접속 endpoint URL",
		content = @Content(schema = @Schema(implementation = String.class))
	)
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = JpaInstanceDescriptor.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404", description = "식별자에 해당하는 MDTInstance가 존재하지 않는 경우.")
    })
    @PostMapping("/registry/{id}")
    public ResponseEntity<?> registerInstance(@PathVariable("id") String id, @RequestBody String repoEndpoint)
    	throws InterruptedException {
    	if ( !(m_instanceManager instanceof ExternalInstanceManager) ) {
			throw new MDTInstanceManagerException(
					"MDTInstance registration is only supported for ExternalInstanceManager: type="
					+ m_instanceManager.getClass().getName());
    	}
    	
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
        	ExternalInstanceManager extInstMgr = (ExternalInstanceManager)m_instanceManager;
        	ExternalInstance intance = extInstMgr.register(id, repoEndpoint);

    		return ResponseEntity.ok(m_serde.toJson(intance.getInstanceDescriptor()));
    	}
    	catch ( ResourceNotFoundException e ) {
    		String msg = String.format("MDTInstance is not present: id=%s, cause=%s", id, e.getMessage());
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(RESTfulErrorEntity.of(msg, e));
    	}
    }

    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTInstance의 접속을 해제시킨다.")
    @Parameters({
    	@Parameter(name = "id", description = "접속 해제시킬 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "204", description = "성공"),
    	@ApiResponse(responseCode = "404", description = "식별자에 해당하는 MDTInstance가 접속되어 있지 않습니다.")
    })
    @DeleteMapping("/registry/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregisterInstance(@PathVariable("id") String id) {
		if ( m_instanceManager instanceof ExternalInstanceManager extInstMgr ) {
	    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
	    		extInstMgr.unregister(id);
	    	}
		}
		else {
			throw new UnsupportedOperationException("unregisterInstance: not supported");
		}
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
    	JpaInstance inst = m_instanceManager.getInstance(id);
    	try {
			String log = inst.getOutputLog();
			return ResponseEntity.ok(log);
		}
		catch ( IOException e ) {
			String msg = String.format("Failed to read MDTInstance(%s) log", id);
			return ResponseEntity.internalServerError().body(RESTfulErrorEntity.of(msg, e));
		}
    }
    
    @Operation(summary = "주어진 참조 표현식에 해당하는 SubmodelElement를 반환한다.")
    @Parameters({
    	@Parameter(name = "ref", description = "참조 표현식")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = SubmodelElement.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "400", description = "참조 표현식에 오류가 있는 경우."),
    	@ApiResponse(responseCode = "403", description = "참조 표현식과 관련된 resource가 비 정상적인 상태인 경우."),
    	@ApiResponse(responseCode = "404", description = "참조 표현식과 관련된 resource가 존재하지 않는 경우."),
    	@ApiResponse(responseCode = "500", description = "참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @GetMapping("/references/{ref}")
    public ResponseEntity<?> getReferenceElement(@PathVariable("ref") String refString) {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref, String refString) throws IOException, IllegalArgumentException {
        		SubmodelElement sme = ref.read();
        		return ResponseEntity.ok(MDTModelSerDe.toJsonString(sme));
    		}
    		
    		@Override
        	public ResponseEntity<?> handle(DefaultSubmodelReference ref, String refString) throws IOException, IllegalArgumentException {
    			Submodel sm = ref.get().getSubmodel();
        		return ResponseEntity.ok(MDTModelSerDe.toJsonString(sm));
        	}
    	});
    }
    
    @Operation(summary = "참조 표현식에 해당하는 SubmodelElement를 갱신한다.")
    @Parameters({
    	@Parameter(name = "ref", description = "갱신 대상 참조 표현식")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	description = "갱신할 새 SubmodelElement의 JSON 표현식",
		content = @Content(schema = @Schema(implementation = SubmodelElement.class))
	)
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = SubmodelElement.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "400", description = "참조 표현식에 오류가 있는 경우."),
    	@ApiResponse(responseCode = "403", description = "참조 표현식과 관련된 resource가 비 정상적인 상태인 경우."),
    	@ApiResponse(responseCode = "404", description = "참조 표현식과 관련된 resource가 존재하지 않는 경우."),
    	@ApiResponse(responseCode = "500", description = "참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @PutMapping("/references/{ref}")
    public ResponseEntity<?> putReferenceElement(@PathVariable("ref") String refString,
												@RequestBody String newElementJson) throws IOException {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref, String refString) throws IOException, IllegalArgumentException {
    			SubmodelElement newElement = MDTModelSerDe.readValue(newElementJson, SubmodelElement.class);
    			ElementValue smev = ElementValues.getValue(newElement);
    			ref.updateValue(smev);
    			return ResponseEntity.noContent().build();
    		}
    	});
    }

    @Operation(summary = "참조 표현식에 해당하는 SubmodelElement의 ValueOnly Serialization을 반환한다.")
    @Parameters({
    	@Parameter(name = "ref", description = "참조 표현식")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = SubmodelElement.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "400", description = "참조 표현식에 오류가 있는 경우."),
    	@ApiResponse(responseCode = "403", description = "참조 표현식과 관련된 resource가 비 정상적인 상태인 경우."),
    	@ApiResponse(responseCode = "404", description = "참조 표현식과 관련된 resource가 존재하지 않는 경우."),
    	@ApiResponse(responseCode = "500", description = "참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @GetMapping("/references/{ref}/$value")
    public ResponseEntity<?> getReferenceValue(@PathVariable("ref") String refString) {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref, String refString) throws IOException, IllegalArgumentException {
        		ElementValue smev = ref.readValue();
        		return ResponseEntity.ok(smev.toValueJsonString());
    		}
    	});
    }
    
    @Operation(summary = "참조 표현식에 해당하는 SubmodelElement의 ValueOnly Serialization을 이용해 갱신한다.")
    @Parameters({
    	@Parameter(name = "ref", description = "갱신 대상 참조 표현식")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	description = "갱신할 새 SubmodelElement의 ValueOnly Serialization",
		content = {
			@Content(schema = @Schema(implementation = String.class), mediaType = "application/json")
		}
	)
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "204", description = "성공"),
    	@ApiResponse(responseCode = "400", description = "참조 표현식에 오류가 있는 경우."),
    	@ApiResponse(responseCode = "403", description = "참조 표현식과 관련된 resource가 비 정상적인 상태인 경우."),
    	@ApiResponse(responseCode = "404", description = "참조 표현식과 관련된 resource가 존재하지 않는 경우."),
    	@ApiResponse(responseCode = "500", description = "참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @PutMapping("/references/{ref}/$value")
    public ResponseEntity<?> putReferenceValue(@PathVariable("ref") String refString,
												@RequestBody String newElementValueJson) throws IOException {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref, String refString)
        		throws IOException, IllegalArgumentException {
    			ref.updateWithValueJsonString(newElementValueJson);
    			return ResponseEntity.noContent().build();
    		}
    	});
    }

	/**
	 * 주어진 element reference를 해석하여 이를 접근하기 위한 restful URI를 반환한다.
	 *
	 * @param refString	element reference expression.
	 * @return	해석된 element reference에 해당하는 restful URI.
	 */
    @Operation(summary = "참조 표현식에 해당하는 SubmodelElement을 접근하는 URL을 반환한다.")
    @Parameters({
    	@Parameter(name = "ref", description = "참조 표현식")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
					content = {@Content(schema = @Schema(implementation = String.class))}),
    	@ApiResponse(responseCode = "400", description = "참조 표현식에 오류가 있는 경우."),
    	@ApiResponse(responseCode = "403", description = "참조 표현식과 관련된 resource가 비 정상적인 상태인 경우."),
    	@ApiResponse(responseCode = "404", description = "참조 표현식과 관련된 resource가 존재하지 않는 경우."),
    	@ApiResponse(responseCode = "500", description = "참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @GetMapping("/references/{ref}/$url")
    public ResponseEntity<?> getReferenceUrl(@PathVariable("ref") String refString) {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref, String refString) throws IOException, IllegalArgumentException {
    			SubmodelBasedElementReference smbeRef = (SubmodelBasedElementReference)ref;
	    		String instId = ref.getInstanceId();
	    		String smId = smbeRef.getSubmodelReference().getSubmodelId();
	    		String idShortPath = smbeRef.getIdShortPath().toString();
	    		String baseEp = m_instanceManager.getInstanceDescriptor(instId).getBaseEndpoint();
	    		String reqUrl = String.format("%s/submodels/%s/submodel-elements/%s",
	    										baseEp, AASUtils.encodeBase64UrlSafe(smId),
	    										AASUtils.encodeIdShortPath(idShortPath));
	    		
				return ResponseEntity.ok(reqUrl);
    		}
    		
    		@Override
        	public ResponseEntity<?> handle(DefaultSubmodelReference ref, String refString) throws IOException, IllegalArgumentException {
        		String instId = ref.getInstanceId();
        		String smId = ref.getSubmodelId();
        		String baseEp = m_instanceManager.getInstanceDescriptor(instId).getBaseEndpoint();
    			String reqUrl = String.format("%s/submodels/%s", baseEp, AASUtils.encodeBase64UrlSafe(smId));
        		
    			return ResponseEntity.ok(reqUrl);
        	}
    	});
    }

    @Operation(summary = "참조 표현식의 JSON 표현식을 반환한다.")
    @Parameters({
    	@Parameter(name = "ref", description = "참조 표현식")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = MDTElementReference.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "400", description = "참조 표현식에 오류가 있는 경우."),
    	@ApiResponse(responseCode = "403", description = "참조 표현식과 관련된 resource가 비 정상적인 상태인 경우."),
    	@ApiResponse(responseCode = "404", description = "참조 표현식과 관련된 resource가 존재하지 않는 경우."),
    	@ApiResponse(responseCode = "500", description = "참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @GetMapping("/references/{ref}/$json")
    public ResponseEntity<?> getReferenceJson(@PathVariable("ref") String refString) {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref, String refString) throws IOException, IllegalArgumentException {
				return ResponseEntity.ok(MDTModelSerDe.toJsonString(ref));
    		}
    		
    		@Override
        	public ResponseEntity<?> handle(DefaultSubmodelReference ref, String refString) throws IOException, IllegalArgumentException {
				return ResponseEntity.ok(MDTModelSerDe.toJsonString(ref));
        	}
    	});
    }

    @Operation(summary = "표현식으로 정의된 변수의 JSON 표현식을 반환한다.")
    @Parameters({
    	@Parameter(name = "expr", description = "표현식")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = MDTElementReference.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "400", description = "참조 표현식에 오류가 있는 경우."),
    	@ApiResponse(responseCode = "403", description = "참조 표현식과 관련된 resource가 비 정상적인 상태인 경우."),
    	@ApiResponse(responseCode = "404", description = "참조 표현식과 관련된 resource가 존재하지 않는 경우."),
    	@ApiResponse(responseCode = "500", description = "참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @GetMapping("/variables/{expr}/$json")
    public ResponseEntity<?> getVariableJson(@PathVariable("expr") String exprString) {
    	return handleReference(exprString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref, String refString) throws IOException, IllegalArgumentException {
				Variable var = Variables.newInstance("", "", ref);
				return ResponseEntity.ok(Variables.toJsonString(var));
    		}
    		
    		@Override
        	public ResponseEntity<?> handle(ElementValue literal, String refString) throws IOException, IllegalArgumentException {
    			Variable var = Variables.newInstance("", "", literal);
    			return ResponseEntity.ok(Variables.toJsonString(var));
        	}
    	});
    }
    
    public interface ReferenceHandler {
    	public ResponseEntity<?> handle(MDTElementReference ref, String refString)
			throws IOException, IllegalArgumentException;
    	default public ResponseEntity<?> handle(DefaultSubmodelReference ref, String refString)
			throws IOException, IllegalArgumentException {
			String msg = String.format("SubmodelReference is not supported for value: expr=%s", refString);
			return ResponseEntity.badRequest().body(RESTfulErrorEntity.ofMessage(msg));
    	}
    	default public ResponseEntity<?> handle(ElementValue literal, String refString)
			throws IOException, IllegalArgumentException {
			String msg = String.format("Literal is not supported for value: expr=%s", refString);
			return ResponseEntity.badRequest().body(RESTfulErrorEntity.ofMessage(msg));
    	}
    };
    private ResponseEntity<?> handleReference(String refString, ReferenceHandler handler) {
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
    		Object ref = parseReference(refString);
    		if ( ref instanceof MDTElementReference elmRef ) {
    			return handler.handle(elmRef, refString);
    		}
    		else if ( ref instanceof DefaultSubmodelReference smRef ) {
    			return handler.handle(smRef, refString);
    		}
			else if ( ref instanceof LiteralExpr lit ) {
				return handler.handle(lit.evaluate(), refString);
			}
    		else {
    			String msg = String.format("Unexpected reference expression: expr=%s", refString);
				return ResponseEntity.badRequest().body(RESTfulErrorEntity.ofMessage(msg));
    		}
    	}
		catch ( IllegalArgumentException e ) {
			return ResponseEntity.badRequest().body(RESTfulErrorEntity.of(e));
		}
		catch ( IOException e ) {
			String msg = String.format("Failed to read reference: reference=%s, cause=%s", refString, e.getMessage());
			return ResponseEntity.internalServerError().body(RESTfulErrorEntity.of(msg, e));
		}
    	catch ( ResourceNotFoundException e ) {
    		String msg = String.format("Reference not found: expr=%s, cause=%s", refString, e.getMessage());
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(RESTfulErrorEntity.of(msg, e));
    	}
    	catch ( InvalidResourceStatusException e ) {
    		String msg = String.format("Invalid resource status: expr=%s, cause=%s", refString, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(RESTfulErrorEntity.of(msg, e));
    	}
		catch ( Exception e ) {
			String msg = String.format("Failed to parse variable: expr=%s, cause=%s", refString, e.getMessage());
			RESTfulErrorEntity error = RESTfulErrorEntity.of(msg, e);
			return ResponseEntity.internalServerError().body(error);
		}
    }

    @PostMapping("/initializeOperationVariables")
    public ResponseEntity<?> initializeOperationVariables(@RequestParam("reference") String opRefExpr,
    													@RequestBody String initializer) throws IOException {
    	List<Variable> initVars = MDTModelSerDe.readValueList(initializer, Variable.class);
    	Map<String,Variable> initializers = FStream.from(initVars)
									    			.peek(var -> {
									    				if ( var instanceof ReferenceVariable refVar ) {
									    					refVar.activate(m_instanceManager);
									    				}
									    			})
									    			.mapToKeyValue(var -> KeyValue.of(var.getName(), var))
									    			.toMap();
    	
    	try ( JpaSession session = m_instanceManager.allocateJpaSession() ) {
    		ElementReference ref = ElementReferences.parseExpr(opRefExpr);
			if ( ref instanceof MDTElementReference mdtRef ) {
				mdtRef.activate(m_instanceManager);
			}
			SubmodelElement sme = ref.read();
			if ( !(sme instanceof org.eclipse.digitaltwin.aas4j.v3.model.Operation) ) {
				RESTfulErrorEntity error = RESTfulErrorEntity.ofMessage("Element is not Operation: " + opRefExpr);
				return ResponseEntity.badRequest().body(error);
			}
			org.eclipse.digitaltwin.aas4j.v3.model.Operation op
														= (org.eclipse.digitaltwin.aas4j.v3.model.Operation)sme;
			FStream.from(op.getInputVariables())
					.concatWith(FStream.from(op.getInoutputVariables()))
					.forEachOrThrow(var -> {
						SubmodelElement varSme = var.getValue();
						Variable initVar = initializers.get(varSme.getIdShort());
						ElementValues.update(varSme, initVar.readValue());
					});
			String opJson = MDTModelSerDe.toJsonString(op);
    		return ResponseEntity.ok(opJson);
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
    		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(RESTfulErrorEntity.of(cause));
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
    
    private Object parseReference(String refString) {
		MDTExpr expr = MDTExprParser.parseExpr(refString);
		if ( expr instanceof MDTElementReferenceExpr refExpr ) {
			try {
				MDTElementReference ref = refExpr.evaluate();
				ref.activate(m_instanceManager);
				return ref;
			}
			catch ( ResourceNotFoundException | InvalidResourceStatusException e ) {
				throw e;
			}
			catch ( Exception e ) {
				Throwable cause = Throwables.unwrapThrowable(e);
				String msg = String.format("Failed to parse reference: expr=%s, cause=%s", refString, cause);
				throw new IllegalArgumentException(msg);
			}
		}
		else if ( expr instanceof MDTSubmodelExpr smExpr ) {
			DefaultSubmodelReference smRef = smExpr.evaluate();
			smRef.activate(m_instanceManager);
			return smRef;
		}
		else {
			String msg = String.format("Unexpected reference expression: expr=%s", refString);
			throw new IllegalArgumentException(msg);
		}
    }
    
    private File buildBundle(String id, MultipartFile zippedBundle) throws IOException {
    	File bundleDir = new File(m_instanceManager.getBundlesDir(), id);
    	
		// 동일 이름의 directory가 존재할 수도 있기 때문에 해당 디렉토리가 있으면 삭제한다.
		FileUtils.deleteDirectory(bundleDir);
		
    	// 입력 bundle zip파일의 압축을 푼다.
		File zippedBundleFile = downloadFile(m_instanceManager.getBundlesDir(),
											zippedBundle.getOriginalFilename(), zippedBundle);
		new ZipFile(zippedBundleFile.toPath()).unzip(bundleDir.toPath());
		zippedBundleFile.delete();
		
		return bundleDir;
    }

//	private File buildBundle(String id, MultipartFile mpfJar, MultipartFile mpfModel, MultipartFile mpfConf)
//		throws IOException {
//    	File bundleDir = new File(m_instanceManager.getBundlesDir(), id);
//    	FileUtils.createDirectory(bundleDir);
//    	
//    	File mgrHomeDir = m_instanceManager.getHomeDir();
//    	
//		// 동일 이름의 directory가 존재할 수도 있기 때문에 해당 디렉토리가 있으면
//		// 먼저 삭제하고, 다시 directory를 생성한다.
//		FileUtils.deleteDirectory(bundleDir);
//
//		try {
//			FileUtils.createDirectory(bundleDir);
//			
//			// AAS 초기 모델 파일과 설정 파일을 생성한 bundle 디렉토리로 download 받는다.
//			downloadFile(bundleDir, mpfModel.getOriginalFilename(), mpfModel);
//			downloadFile(bundleDir, mpfConf.getOriginalFilename(), mpfConf);
//			
//			// mpfJar은 null이 될 수 있기 때문에 null이 아닌 경우만 jar 파일을 복사한다.
//			// null인 경우에는 default jar를 복사한다.
////			if ( mpfJar != null ) {
////				downloadFile(bundleDir, MDTInstanceManager.FA3ST_JAR_FILE_NAME, mpfJar);
////			}
////			else {
////				File defaultJarFile = m_instanceManager.getDefaultMDTInstanceJarFile();
////				if ( defaultJarFile == null || !defaultJarFile.exists() ) {
////					throw new IllegalStateException("No default MDTInstance jar file exists: path=" + defaultJarFile);
////				}
////				
////				FileUtils.copy(defaultJarFile, new File(bundleDir, MDTInstanceManager.FA3ST_JAR_FILE_NAME));
////			}
//
//			File srcGlobalConfFile = new File(mgrHomeDir, MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
//			if ( srcGlobalConfFile.exists() ) {
//				File globalConfFile = new File(bundleDir, MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
//				Files.copy(srcGlobalConfFile.toPath(), globalConfFile.toPath());
//			}
//
//			File defaultCertFile = new File(mgrHomeDir, MDTInstanceManager.CERT_FILE_NAME);
//			if ( defaultCertFile.exists() ) {
//				File certFile = new File(bundleDir, MDTInstanceManager.CERT_FILE_NAME);
//				FileUtils.copy(defaultCertFile, certFile);
//			}
//
//			// Dockerfile 파일을 복사한다.
//			File srcDockerfile = new File(mgrHomeDir, DOCKER_FILE);
//			File tarDockerfile = new File(bundleDir, DOCKER_FILE);
//			FileUtils.copy(srcDockerfile, tarDockerfile);
//			
//			return bundleDir;
//		}
//		catch ( IOException e ) {
//			Try.run(() -> FileUtils.deleteDirectory(bundleDir));
//			throw e;
//		}
//		catch ( Throwable e ) {
//			Try.run(() -> FileUtils.deleteDirectory(bundleDir));
//			throw e;
//		}
//	}
    
    private File downloadFile(File topDir, String fileName, MultipartFile mpf) throws IOException {
		File file = new File(topDir, fileName);
		IOUtils.toFile(mpf.getInputStream(), file);
		return file;
    }
}
