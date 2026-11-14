package mdt.controller;

import static mdt.controller.JsonResponses.jsonOk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonSerializer;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Lists;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import utils.Throwables;
import utils.func.Nullables;
import utils.func.Try;
import utils.http.RESTfulErrorEntity;
import utils.io.FileUtils;
import utils.io.IOUtils;
import utils.io.ZipFile;
import utils.stream.FStream;

import mdt.Globals;
import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.instance.external.ExternalInstance;
import mdt.instance.external.ExternalInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AASUtils;
import mdt.model.InvalidResourceStatusException;
import mdt.model.MDTModelSerDe;
import mdt.model.ModelValidationException;
import mdt.model.ResourceNotFoundException;
import mdt.model.expr.MDTElementReferenceExpr;
import mdt.model.expr.MDTExpression;
import mdt.model.expr.MDTExpressionParser;
import mdt.model.expr.MDTParserException;
import mdt.model.expr.MDTSubmodelExpr;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTModel;
import mdt.model.instance.MDTOperationDescriptor;
import mdt.model.instance.MDTParameterDescriptor;
import mdt.model.instance.MDTSubmodelDescriptor;
import mdt.model.instance.MDTTwinCompositionDescriptor;
import mdt.model.sm.ref.DefaultSubmodelReference;
import mdt.model.sm.ref.ElementReference;
import mdt.model.sm.ref.ElementReferences;
import mdt.model.sm.ref.MDTElementReference;
import mdt.model.sm.ref.SubmodelBasedElementReference;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.workflow.model.ArgumentSpec;
import mdt.workflow.model.ArgumentSpec.LiteralArgument;
import mdt.workflow.model.ArgumentSpec.ReferenceArgument;
import mdt.workflow.model.ArgumentSpec.TaskOutputArgument;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
@RestController
@RequestMapping(value={"/instance-manager"})
public class MDTInstanceManagerController implements InitializingBean {
	private final Logger s_logger = LoggerFactory.getLogger(MDTInstanceManagerController.class);
	private final JsonSerializer SERIALIZER = MDTModelSerDe.JSON_SERIALIZER;
	
	@Autowired AbstractJpaInstanceManager<? extends JpaInstance> m_instanceManager;
	@Value("${server.host}")
	private String m_host;
	@Value("${server.port}")
	private int m_port;

	@Override
	public void afterPropertiesSet() throws Exception {
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("{} is ready to serve: {}:{}", getClass().getName(), m_host, m_port);
		}
	}

    @Tag(name = "MDTInstance 관리")
    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTInstance 등록정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description="검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(schema = @Schema(implementation=InstanceDescriptor.class), mediaType="application/json")
			}),
    	@ApiResponse(responseCode="404",
    		description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
			content = {
				@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
			})
    })
    @GetMapping("/instances/{id}")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> getInstance(@PathVariable("id") String id) throws IOException {
    	JpaInstanceDescriptor desc = m_instanceManager.getInstanceDescriptor(id);
    	InstanceDescriptor instDesc = desc.toInstanceDescriptor();
    	String json = MDTModelSerDe.toJsonString(instDesc);
		return jsonOk(json);
    }

    @Tag(name = "MDTInstance 관리")
    @Operation(summary = "MDTInstanceManager에 등록된 모든 MDTInstance 등록정보들을 반환한다.")
    @Parameters({
    	@Parameter(name = "filter", description="검색 필터 표현식. 'instance' 객체의 속성 값을"
    											+ "활용하여 SQL의 WHERE 절에 사용되는 표현식을 지정한다. ",
    				example = "instance.idShort like \"abc%\"")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
    		content = {
    			@Content(mediaType="application/json",
    					array = @ArraySchema(schema=@Schema(implementation=InstanceDescriptor.class)))
    		}
    	)
    })
    @GetMapping("/instances")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> getInstanceAll(@RequestParam(name="filter", required=false) String filter) {
    	List<MDTInstance> matches = findInstances(filter);
		List<InstanceDescriptor> descList = FStream.from(matches)
													.map(MDTInstance::getInstanceDescriptor)
													.toList();
		return jsonOk(MDTModelSerDe.toJsonString(descList));
    }

    @Tag(name = "MDTInstance 모델 조회")
    @Operation(summary = "MDTInstanceManager에 등록된 모든 MDTInstance들의 MDT Model 전체 정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "filter", description="검색 필터 표현식. 'instance' 객체의 속성 값을"
    											+ "활용하여 SQL의 WHERE 절에 사용되는 표현식을 지정한다. ",
    				example = "instance.idShort like \"abc%\"")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
    		content = {
    			@Content(mediaType="application/json",
    					array = @ArraySchema(schema=@Schema(implementation=MDTModel.class)))
    		})
    })
    @GetMapping("/instances/model")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> getMDTModelAll(@RequestParam(name="filter", required=false) String filter) {
    	List<MDTInstance> matches = findInstances(filter);
    	
    	List<MDTModel> models = Lists.newArrayList();
    	for ( JpaInstance instance: FStream.from(matches).cast(JpaInstance.class) ) {
    		models.add(toMDTModel(instance));
    	}
		
		return jsonOk(MDTModelSerDe.toJsonString(models));
    }

    @Tag(name = "MDTInstance 관리")
    @Operation(summary = "등록된 MDTInstance의 수를 반환한다.")
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
    				content = {@Content(mediaType = "text/plain", schema = @Schema(type = "integer"))})
    })
    @GetMapping("/instances/$count")
    public ResponseEntity<String> countInstances() {
    	// mdt-client HttpMDTInstanceManager.countInstances()가 부른다(2026-11-05 추가, 검토 D30).
    	return ResponseEntity.ok()
    						.contentType(MediaType.TEXT_PLAIN)
    						.body(Long.toString(m_instanceManager.countInstances()));
    }

    @Tag(name = "MDTInstance 관리")
    @Operation(summary = "MDTInstanceManager에 새로운 MDTInstance를 등록시킨다.")
    @Parameters({
    	@Parameter(name = "id", description="등록 MDTInstance 식별자"),
    	@Parameter(name = "port", description="MDTInstance 서비스(FA3ST) 포트 번호. 생략하면 자동으로 결정된다."),
    	@Parameter(name = "bundle", description="등록시킬 MDTInstance의 모델 디렉토리를 zip으로 압축한 파일")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	required=true,
		description="등록시킬 MDTInstance의 모델 디렉토리를 zip으로 압축한 파일 내용"
	)
    @ApiResponses(value = {
        	@ApiResponse(responseCode="201", description="인스턴스 등록 성공",
		    			content = {
		    				@Content(schema = @Schema(implementation=InstanceDescriptor.class),
		    						mediaType="application/json")
		    			}),
	    	@ApiResponse(responseCode="409",
			    		description="동일 식별자의 MDTInstance가 이미 등록되어 있습니다.",
						content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						})
        })
    @PostMapping({"/instances"})
    @ResponseStatus(HttpStatus.CREATED)
    public String addInstance(@RequestParam("id") String id,
    							@RequestParam(name = "port", defaultValue = "-1") int port,
								@RequestParam(name="bundle", required=true) MultipartFile zipFile)
		throws IOException, ModelValidationException, MDTInstanceManagerException {
    	// id는 bundle/instance 디렉터리 이름으로 쓰이므로 무엇보다 먼저 검사한다(잘못되면 400).
    	AbstractJpaInstanceManager.checkInstanceId(id);
		Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADDING(id));
		
		// ADDING을 알린 뒤에는 어떤 실패든(번들 압축 해제 포함) ADD_FAILED로 끝나야 한다. 2026-11-05 이전에는 번들을
		// 만드는 단계가 try 밖이라, 그 단계에서 실패하면 ADD_FAILED 없이 ADDING에 멈췄다.
		boolean added = false;
		File workDir = null;
    	try {
	    	// 입력 받은 zip 파일을 요청마다 따로 만든 작업 디렉터리에 풀어 bundle 디렉터리를 만든다.
    		workDir = Files.createTempDirectory(m_instanceManager.getBundlesDir().toPath(), id + "-").toFile();
    		File bundleDir = buildBundle(workDir, zipFile);
    		
	    	// Bundle directory의 내용을 이용해서 InstanceDescriptor를 생성하여 등록하고,
    		// 이를 통해 MDTInstance를 생성한다.
    		MDTInstance inst = m_instanceManager.addInstance(id, port, bundleDir);
    		added = true;
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADDED(id));
    		
    		// auto-start 옵션이 설정되어 있으면 바로 시작시킨다. 시작이 실패해도 등록은 이미 끝났으므로 201로
    		// 응답하고, 실패는 인스턴스 상태(FAILED)와 로그로 알린다. 2026-11-05 이전에는 시작 실패를 등록 실패
    		// (ADD_FAILED, 500)로 보고해, 다시 등록하면 409가 났다.
			if ( m_instanceManager.getConfiguration().isAutoStart() ) {
				s_logger.info("auto-starting the newly added MDTInstance: id={}", id);
				try {
					inst.start(null, null);
				}
				catch ( Exception e ) {
					s_logger.warn("failed to auto-start the newly added MDTInstance: id={}, cause={}", id,
									"" + Throwables.unwrapThrowable(e));
				}
			}
    		
			return MDTModelSerDe.toJsonString(m_instanceManager.getInstance(id).getInstanceDescriptor());
    	}
		catch ( IOException | RuntimeException e ) {
			throw e;
		}
    	catch ( Exception e ) {
    		// Error는 감싸지 않고 그대로 전파된다(2026-11-05 이전에는 Throwable을 잡아 감쌌다).
			throw new MDTInstanceManagerException("failed to add MDTInstance: id=" + id, Throwables.unwrapThrowable(e));
    	}
    	finally {
    		if ( !added ) {
    			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADD_FAILED(id));
    		}
	    	// 작업 디렉터리(업로드 zip, bundle)는 인스턴스 디렉터리로 복사·이동한 뒤에는 필요 없다.
    		if ( workDir != null ) {
    			Try.accept(workDir, FileUtils::deleteDirectory);
    		}
    	}
    }

    @Tag(name = "MDTInstance 관리")
    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTInstance 등록정보를 삭제한다.")
    @Parameters({
    	@Parameter(name = "id", description="검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="204", description="성공"),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					})
    })
    @DeleteMapping("/instances/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<?> removeInstance(@PathVariable("id") String id) {
    	m_instanceManager.removeInstance(id);
    	return ResponseEntity.noContent().build();
    }

    @Tag(name = "MDTInstance 관리")
    @Operation(summary = "MDTInstanceManager에 등록된 모든 MDTInstance 등록정보를 삭제한다.")
    @ApiResponses(value = {
    	@ApiResponse(responseCode="204", description="성공"),
    })
    @DeleteMapping("/instances")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeInstanceAll() {
		m_instanceManager.removeInstanceAll();
    }

    @Tag(name = "MDTInstance 관리")
    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTInstance를 시작시킨다.")
    @Parameters({
    	@Parameter(name = "id", description="시작시킬 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(schema = @Schema(implementation=InstanceDescriptor.class), mediaType="application/json")
			}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						}),
    	@ApiResponse(responseCode="409",
    				description="식별자에 해당하는 MDTInstance가 이미 실행 중인 경우.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						}),
    	@ApiResponse(responseCode="408",
    				description="제한된 시간 내에 MDTInstance가 시작되지 못한 경우.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						}),
    	@ApiResponse(responseCode="500",
					description="MDTInstance 시작 과정 중 서버 내부 오류로 실패한 경우.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						})
    })
    @PutMapping("/instances/{id}/start")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> startInstance(@PathVariable("id") String id)
    	throws IOException, InterruptedException, InvalidResourceStatusException, TimeoutException, ExecutionException {
    	JpaInstance inst = m_instanceManager.getInstance(id);

		inst.start(null, null);

		JpaInstanceDescriptor desc = m_instanceManager.getInstanceDescriptor(id);
		return jsonOk(MDTModelSerDe.toJsonString(desc.toInstanceDescriptor()));
    }

    @Tag(name = "MDTInstance 관리")
    @Operation(
    	summary = "MDTInstance 식별자에 해당하는 MDTInstance를 중지시킨다.",
    	description="메소드는 MDTInstance의 중지 요청을 수행하고, 완전히 중지되기 전에 반환될 수도 있다. "
    )
    @Parameters({
    	@Parameter(name = "id", description="중지시킬 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(schema = @Schema(implementation=InstanceDescriptor.class), mediaType="application/json")
			}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class),
									mediaType="application/json")
						}),
    	@ApiResponse(responseCode="409",
    				description="식별자에 해당하는 MDTInstance가 실행 중이지 않은 경우.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class),
									mediaType="application/json")
						})
    })
    @PutMapping("/instances/{id}/stop")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> stopInstance(@PathVariable("id") String id)
    	throws IOException, InterruptedException, TimeoutException, InvalidResourceStatusException {
    	JpaInstance inst = m_instanceManager.getInstance(id);
    	inst.stop(null, null);

    	JpaInstanceDescriptor desc = m_instanceManager.getInstanceDescriptor(id);
    	return jsonOk(MDTModelSerDe.toJsonString(desc.toInstanceDescriptor()));
    }

    @Tag(name = "MDTInstance 관리")
    @Operation(summary = "MDTInstance을 등록시킨다.")
    @Parameters({
    	@Parameter(name = "id", description="등록시킬 MDTInstance 식별자")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	description="등록시킬 MDTInstance의 접속 endpoint URL",
		content = @Content(schema = @Schema(implementation=String.class))
	)
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(schema = @Schema(implementation=InstanceDescriptor.class), mediaType="application/json")
			}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class),
									mediaType="application/json")
						}),
    	@ApiResponse(responseCode="501",
					description="MDTInstanceManager가 ExternalInstanceManager가 아닌 경우.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class),
									mediaType="application/json")
						})
    })
    @PostMapping("/registry/{id}")
    public ResponseEntity<?> registerInstance(@PathVariable("id") String id, @RequestBody String repoEndpoint)
    	throws InterruptedException, IOException {
    	if ( !(m_instanceManager instanceof ExternalInstanceManager) ) {
    		// 지원하지 않는 기능은 501이다(advice). 2026-11-05 이전에는 405(메소드 불일치용 코드)였다.
    		throw new UnsupportedOperationException("MDTInstance registration is only supported for "
								+ "ExternalInstanceManager: type=" + m_instanceManager.getClass().getName());
    	}
    	
    	ExternalInstanceManager extInstMgr = (ExternalInstanceManager)m_instanceManager;
    	ExternalInstance intance = extInstMgr.register(id, repoEndpoint);

		return jsonOk(MDTModelSerDe.toJsonString(intance.getInstanceDescriptor()));
    }

    @Tag(name = "MDTInstance 관리")
    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTInstance의 접속을 해제시킨다.")
    @Parameters({
    	@Parameter(name = "id", description="접속 해제시킬 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="204", description="성공"),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class),
									mediaType="application/json")
						}),
    	@ApiResponse(responseCode="501",
					description="MDTInstanceManager가 ExternalInstanceManager가 아닌 경우.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class),
									mediaType="application/json")
						})
    })
    @DeleteMapping("/registry/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<?> unregisterInstance(@PathVariable("id") String id) {
		if ( m_instanceManager instanceof ExternalInstanceManager extInstMgr ) {
	    	extInstMgr.unregister(id);
	    	return ResponseEntity.noContent().build();
		}
		else {
    		// 2026-11-05 이전에는 405였고, 메시지가 "registration"으로 복사되어 있었다.
			throw new UnsupportedOperationException("MDTInstance unregistration is only supported for "
								+ "ExternalInstanceManager: type=" + m_instanceManager.getClass().getName());
		}
    }

    @Tag(name = "MDTInstance 모델 조회")
    @Operation(summary = "MDTInstance 식별자에 해당하는 MDT Model 전체 정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description="검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(schema = @Schema(implementation=MDTModel.class), mediaType="application/json")
			}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						})
    })
    @GetMapping("/instances/{id}/model")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> getMDTModel(@PathVariable("id") String id) {
		JpaInstance instance = m_instanceManager.getInstance(id);
		
		return jsonOk(MDTModelSerDe.toJsonString(toMDTModel(instance)));
    }
    
    @Tag(name = "MDTInstance 모델 조회")
    @Operation(summary = "MDTInstance 식별자에 해당하는 모든 Submodel들의 정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description="검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(array = @ArraySchema(schema = @Schema(implementation=MDTSubmodelDescriptor.class)), mediaType="application/json")
			}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						})
    })
    @GetMapping("/instances/{id}/model/submodels")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> getMDTModelSubmodels(@PathVariable("id") String id) throws SerializationException {
		JpaInstance instance = m_instanceManager.getInstance(id);
		List<MDTSubmodelDescriptor> submodels = instance.getMDTSubmodelDescriptorAll();
		String json = SERIALIZER.write(submodels);
		return jsonOk(json);
    }

    @Tag(name = "MDTInstance 모델 조회")
    @Operation(summary = "MDTInstance 식별자에 해당하는 모든 Parameter들의 정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description="검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(array = @ArraySchema(schema = @Schema(implementation=MDTParameterDescriptor.class)),
												mediaType="application/json")
			}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class),
														mediaType="application/json")
						})
    })
    @GetMapping("/instances/{id}/model/parameters")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> getMDTModelParameters(@PathVariable("id") String id) throws SerializationException {
		JpaInstance instance = m_instanceManager.getInstance(id);
		List<MDTParameterDescriptor> parameters = instance.getMDTParameterDescriptorAll();
		return jsonOk(SERIALIZER.writeList(parameters));
    }

    @Tag(name = "MDTInstance 모델 조회")
    @Operation(summary = "MDTInstance 식별자에 해당하는 모든 연산 (AI/Simulation)들의 정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description="검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(array = @ArraySchema(schema = @Schema(implementation=MDTOperationDescriptor.class)),
												mediaType="application/json")
			}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						})
    })
    @GetMapping("/instances/{id}/model/operations")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> getMDTModelOperations(@PathVariable("id") String id) throws SerializationException {
		JpaInstance instance = m_instanceManager.getInstance(id);
		List<MDTOperationDescriptor> operations = instance.getMDTOperationDescriptorAll();
		return jsonOk(SERIALIZER.writeList(operations));
    }

    @Tag(name = "MDTInstance 모델 조회")
    @Operation(summary = "MDTInstance 식별자에 해당하는 TwinComposition들의 정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description="검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(schema = @Schema(implementation=MDTTwinCompositionDescriptor.class),
											mediaType="application/json")
			}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						})
    })
    @GetMapping("/instances/{id}/model/compositions")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> getMDTModelCompositions(@PathVariable("id") String id) throws SerializationException {
		JpaInstance instance = m_instanceManager.getInstance(id);
		MDTTwinCompositionDescriptor twinComp = instance.getMDTTwinCompositionDescriptor();
		return jsonOk(SERIALIZER.write(twinComp));
    }

    @Tag(name = "MDTInstance 모델 조회")
    @Operation(summary = "MDTInstance에 포함된 AssetAdministrationShell 모델 기술자를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description="대상 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(schema = @Schema(implementation=AssetAdministrationShellDescriptor.class), mediaType="application/json")
			}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						})
    })
    @GetMapping({"instances/{id}/aas/shell_descriptor"})
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<String> getAssetAdministrationShell(@PathVariable("id") String id)
    	throws SerializationException {
    	// AAS4J 객체는 AAS JSON 직렬화기로 쓴다(클라이언트가 AAS4J로 읽는다). 2026-11-05 이전에는 객체를 그대로
    	// 돌려 Spring 기본 매퍼가 enum을 "INSTANCE"·"GLOBAL_REFERENCE", 필드를 "_interface"로 써서 클라이언트가
    	// 제대로 읽지 못했다(검토 D32).
    	AssetAdministrationShellDescriptor desc = m_instanceManager.getInstance(id).getAASShellDescriptor();
		return jsonOk(MDTModelSerDe.getJsonSerializer().write(desc));
    }

    @Tag(name = "MDTInstance 모델 조회")
    @Operation(summary = "MDTInstance에 포함된 모든 Submodel 기술자들을 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description="대상 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
	        		content = {
            			@Content(mediaType="application/json",
            					array = @ArraySchema(schema=@Schema(implementation=SubmodelDescriptor.class)))
            		}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					})
    })
    @GetMapping({"instances/{id}/aas/submodel_descriptors"})
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<String> getSubmodelDescriptors(@PathVariable("id") String id)
    	throws SerializationException {
    	// getAssetAdministrationShell()과 같은 이유로 AAS JSON 직렬화기로 쓴다(검토 D32).
    	List<SubmodelDescriptor> descList = m_instanceManager.getInstance(id).getAASSubmodelDescriptorAll();
		return jsonOk(MDTModelSerDe.getJsonSerializer().writeList(descList));
    }

    @Tag(name = "MDTInstance 관리")
    @Operation(summary = "MDTInstance 식별자에 해당하는 MDTInstance가 생성한 output log 내용을 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description="MDTInstance 식별자")
    })
    @ApiResponses(value = {
        	@ApiResponse(responseCode="200", description="성공",
					content = {
						@Content(schema = @Schema(implementation=String.class), mediaType="text/plain")
					}),
        	@ApiResponse(responseCode="404",
    					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
    					content = {
    							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
    						}),
        	@ApiResponse(responseCode="500",
    					description="Log 파일을 읽는 과정에서 오류가 발생한 경우.",
    					content = {
    							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
    						})
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
    
    @Tag(name = "SubmodelElement 참조 표현식 처리")
    @Operation(summary = "주어진 참조 표현식에 해당하는 SubmodelElement를 반환한다.")
    @Parameters({
    	@Parameter(name = "ref", description="참조 표현식")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
					content = {
						@Content(schema = @Schema(implementation=SubmodelElement.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="400", description="참조 표현식에 오류가 있는 경우.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					})
    })
    @GetMapping("/submodel-element")
    public ResponseEntity<?> readElementOfReference(@RequestParam("ref") String refString) throws Exception {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref) throws Exception {
				SubmodelElement sme = ref.read();
				return jsonOk(MDTModelSerDe.toJsonString(sme));
    		}
    		
    		@Override
        	public ResponseEntity<?> handle(DefaultSubmodelReference ref) throws Exception {
				Submodel sm = ref.get().getSubmodel();
				return jsonOk(MDTModelSerDe.toJsonString(sm));
        	}
    	});
    }

    @Tag(name = "SubmodelElement 참조 표현식 처리")
    @Operation(summary = "참조 표현식에 해당하는 SubmodelElement를 갱신한다.")
    @Parameters({
    	@Parameter(name = "ref", description="갱신 대상 참조 표현식")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	description="갱신할 새 SubmodelElement의 JSON 표현식",
		content = @Content(schema = @Schema(implementation=SubmodelElement.class))
	)
    @ApiResponses(value = {
    	@ApiResponse(responseCode="204", description="성공"),
    	@ApiResponse(responseCode="400", description="참조 표현식에 오류가 있는 경우.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="500", description="참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @PutMapping("/submodel-element")
    public ResponseEntity<?> updateElementOfReference(@RequestParam("ref") String refString,
														@RequestBody String newElementJson) throws Exception {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref) throws Exception {
				SubmodelElement newElement = MDTModelSerDe.readValue(newElementJson, SubmodelElement.class);
				ElementValue smev = ElementValues.getValue(newElement);
				
				ref.updateValue(smev);
				return ResponseEntity.noContent().build();
    		}
    	});
    }
    
    @Tag(name = "SubmodelElement 참조 표현식 처리")
    @Operation(summary = "참조 표현식에 해당하는 SubmodelElement의 값(ElementValue)을 JSON 표현으로 반환한다.")
    @Parameters({
    	@Parameter(name = "ref", description="참조 표현식")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
					content = {
						@Content(schema = @Schema(implementation=String.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="400", description="참조 표현식에 오류가 있는 경우.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="500", description="참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @GetMapping("/submodel-element/$value")
    public ResponseEntity<?> readElementValueOfReference(@RequestParam("ref") String refString) throws Exception {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref) throws Exception {
				ElementValue smev = ref.readValue();
				String jsonStr = smev.toJsonString();
				return jsonOk(jsonStr);
    		}
    	});
    }

    @Tag(name = "SubmodelElement 참조 표현식 처리")
    @Operation(summary = "참조 표현식에 해당하는 SubmodelElement의 ValueOnly Serialization을 반환한다.")
    @Parameters({
    	@Parameter(name = "ref", description="참조 표현식")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(schema = @Schema(implementation=SubmodelElement.class), mediaType="application/json")
			}),
    	@ApiResponse(responseCode="400", description="참조 표현식에 오류가 있는 경우.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						}),
    	@ApiResponse(responseCode="500", description="참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @GetMapping("/submodel-element/$raw")
    public ResponseEntity<?> readRawObjectOfReference(@RequestParam("ref") String refString) throws Exception {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref) throws Exception {
				ElementValue smev = ref.readValue();
				String result = Nullables.mapOrElse(smev, ElementValue::toValueJsonString, "");
				return jsonOk(result);
    		}
    	});
    }

    @Tag(name = "SubmodelElement 참조 표현식 처리")
    @Operation(summary = "참조 표현식에 해당하는 SubmodelElement의 ValueOnly Serialization을 이용해 갱신한다.")
    @Parameters({
    	@Parameter(name = "ref", description="갱신 대상 참조 표현식")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	description="갱신할 새 SubmodelElement의 ValueOnly Serialization",
		content = {
			@Content(schema = @Schema(implementation=String.class), mediaType="application/json")
		}
	)
    @ApiResponses(value = {
    	@ApiResponse(responseCode="204", description="성공"),
    	@ApiResponse(responseCode="400", description="참조 표현식에 오류가 있는 경우.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						}),
    	@ApiResponse(responseCode="500", description="참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @PatchMapping("/submodel-element/$raw")
    public ResponseEntity<?> updateRawObjectOfReference(@RequestParam("ref") String refString,
															@RequestBody String newElementValueJson) throws Exception {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref) throws Exception {
				ref.updateFromValueJson(newElementValueJson);
				return ResponseEntity.noContent().build();
    		}
    	});
    }

    @Tag(name = "SubmodelElement 참조 표현식 처리")
    @Operation(summary = "참조 표현식에 해당하는 SubmodelElement를 주어진 값(ElementValue)의 JSON 표현으로 갱신한다.")
    @Parameters({
    	@Parameter(name = "ref", description="갱신 대상 참조 표현식")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	required=true,
    	description="갱신할 새 값(ElementValue)의 JSON 표현식",
		content = {
			@Content(schema = @Schema(implementation=String.class), mediaType="application/json")
		}
	)
    @ApiResponses(value = {
    	@ApiResponse(responseCode="204", description="성공"),
    	@ApiResponse(responseCode="400", description="참조 표현식에 오류가 있는 경우.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="500", description="참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @PatchMapping("/submodel-element/$value")
    public ResponseEntity<?> updateElementValueOfReference(@RequestParam("ref") String refString,
															@RequestBody String newElementValueJson) throws Exception {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref) throws Exception {
    			ElementValue newValue = ElementValues.parseJsonString(newElementValueJson);
				ref.updateValue(newValue);
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
    @Tag(name = "SubmodelElement 참조 표현식 처리")
    @Operation(summary = "참조 표현식에 해당하는 SubmodelElement을 접근하는 URL을 반환한다.")
    @Parameters({
    	@Parameter(name = "ref", description="참조 표현식")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
					content = {@Content(schema = @Schema(implementation=String.class))}),
    	@ApiResponse(responseCode="400", description="참조 표현식에 오류가 있는 경우.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class),
								mediaType="application/json")
					}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class),
								mediaType="application/json")
					}),
    	@ApiResponse(responseCode="500", description="참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @GetMapping("/references/$url")
    public ResponseEntity<?> getReferenceUrl(@RequestParam(name="ref", required=true) String refString) throws Exception {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref) throws Exception {
				SubmodelBasedElementReference smbeRef = (SubmodelBasedElementReference)ref;
				String instId = ref.getInstanceId();
				String smId = smbeRef.getSubmodelReference().getSubmodelId();
				String idShortPath = smbeRef.getIdShortPathString();
				String baseEp = m_instanceManager.getInstanceDescriptor(instId).getBaseEndpoint();
				String reqUrl = String.format("%s/submodels/%s/submodel-elements/%s",
												baseEp, AASUtils.encodeBase64UrlSafe(smId),
												AASUtils.encodeIdShortPath(idShortPath));
				
				return ResponseEntity.ok(reqUrl);
    		}
    		
    		@Override
        	public ResponseEntity<?> handle(DefaultSubmodelReference ref) throws Exception {
        		String instId = ref.getInstanceId();
        		String smId = ref.getSubmodelId();
        		String baseEp = m_instanceManager.getInstanceDescriptor(instId).getBaseEndpoint();
    			String reqUrl = String.format("%s/submodels/%s", baseEp, AASUtils.encodeBase64UrlSafe(smId));
        		
    			return ResponseEntity.ok(reqUrl);
        	}
    	});
    }

    @Tag(name = "SubmodelElement 참조 표현식 처리")
    @Operation(summary = "참조 표현식의 JSON 표현식을 반환한다.")
    @Parameters({
    	@Parameter(name = "ref", description="참조 표현식")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(schema = @Schema(implementation=MDTElementReference.class), mediaType="application/json")
			}),
    	@ApiResponse(responseCode="400", description="참조 표현식에 오류가 있는 경우.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
							@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
						}),
    	@ApiResponse(responseCode="500", description="참조 표현식 파싱 과정에서 오류가 발생된 경우.")
    })
    @GetMapping("/references/$json")
    public ResponseEntity<?> getReferenceJson(@RequestParam("ref") String refString) throws Exception {
    	return handleReference(refString, new ReferenceHandler() {
    		@Override
        	public ResponseEntity<?> handle(MDTElementReference ref) throws Exception {
				return jsonOk(MDTModelSerDe.toJsonString(ref));
    		}
    		
    		@Override
        	public ResponseEntity<?> handle(DefaultSubmodelReference ref) throws Exception {
				return jsonOk(MDTModelSerDe.toJsonString(ref));
        	}
    	});
    }
    
    
    @Tag(name = "SubmodelElement 참조 표현식 처리")
    @Operation(summary = "표현식으로 정의된 연산 인자(ArgumentSpec)의 JSON 표현식을 반환한다. "
    		+ "Parameter 'ref', 'value', 'taskOutput' 중 하나는 반드시 지정되어야 한다.")
    @Parameters({
    	@Parameter(name = "id", description="인자 식별자", required=true, example="SleepTime"),
    	@Parameter(name = "ref", description="참조 표현식", example="param:test:SleepTime"),
    	@Parameter(name = "value", description="값 리터럴 표현식. 따옴표 없는 숫자·불리언은 해당 타입으로, 단어나 "
    								+ "따옴표로 감싼 값은 문자열로 해석한다(예: 17 → 정수, '17' → 문자열).", example="17"),
    	@Parameter(name = "taskOutput", description="다른 태스크의 출력 인자 참조. 'taskId:outputName' 형식으로 지정한다.",
    				example="simulation:Output"),
    	@Parameter(name = "name", description="인자 이름.", example="SleepTime"),
    	@Parameter(name = "description", description="인자 기술.", example="대기 시간")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공",
			content = {
				@Content(schema = @Schema(implementation=ArgumentSpec.class), mediaType="application/json")
			}),
    	@ApiResponse(responseCode="400",
    				description="'ref', 'value', 'taskOutput'이 모두 지정되지 않았거나, "
    						+ "참조 표현식, 값 리터럴 표현식 또는 'taskOutput' 형식에 오류가 있는 경우.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="404",
					description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					})
    })
    @GetMapping("/argumentSpec/$json")
    public ResponseEntity<?> getArgumentJson(@RequestParam(name="id", required = true) String argId,
    										@RequestParam(name="ref", required = false) String refString,
    										@RequestParam(name="value", required = false) String valueString,
    										@RequestParam(name="taskOutput", required = false) String taskOutputString,
    										@RequestParam(name="name", required=false) String argName,
											@RequestParam(name="description", required=false) String argDesc) throws Exception {
    	if ( argId == null || argId.isEmpty() ) {
			return ResponseEntity.badRequest()
								.body(RESTfulErrorEntity.ofMessage("Argument 'id' must be specified."));
		}
    	if ( refString == null && valueString == null && taskOutputString == null ) {
    		return ResponseEntity.badRequest()
								.body(RESTfulErrorEntity.ofMessage("Either 'ref', 'value', 'taskOutput' parameter must be specified."));
    	}
    	
    	if ( refString != null ) {
        	return handleReference(refString, new ReferenceHandler() {
        		@Override
            	public ResponseEntity<?> handle(MDTElementReference ref) throws Exception {
    				ReferenceArgument arg = ReferenceArgument.builder()
									    					.id(argId)
									    					.name(argName)
									    					.description(argDesc)
									    					.reference(refString)
					    									.build();
    				String json = arg.toJsonString();
    				return jsonOk(json);
        		}
        	});
		}
		else if ( valueString != null ) {
			// 값 리터럴 표현식으로 해석한다(17 → 정수, '17' → 문자열).
			// 2026-11-01 이전에는 항상 문자열 리터럴로 만들어 'value=17'도 문자열 "17"이 되었다.
			ElementValue value;
			try {
				value = ElementValues.parseExpr(valueString);
			}
			catch ( MDTParserException | IllegalArgumentException e ) {
				return ResponseEntity.badRequest()
									.body(RESTfulErrorEntity.ofMessage("Invalid value literal: '" + valueString
																		+ "', cause=" + e.getMessage()));
			}
			LiteralArgument arg = LiteralArgument.builder()
												.id(argId)
												.name(argName)
												.description(argDesc)
												.value(value)
												.build();
			String json = arg.toJsonString();
			return jsonOk(json);
		}
		else if ( taskOutputString != null ) {
			String[] parts = taskOutputString.split(":");
			if ( parts.length != 2 ) {
				return ResponseEntity.badRequest()
									.body(RESTfulErrorEntity.ofMessage("Invalid taskOutput format. Expected 'taskId:outputName'."));
			}
			TaskOutputArgument arg = TaskOutputArgument.builder()
														.id(argId)
														.name(argName)
														.description(argDesc)
														.taskId(parts[0])
														.argumentName(parts[1])
														.build();
			String json = arg.toJsonString();
			return jsonOk(json);

		}
		else {
			throw new AssertionError("unreachable code");
		}
    }
    
    @Tag(name = "SubmodelElement 참조 표현식 처리")
    @Operation(summary = "JSON 표현식으로 주어진 참조를 해석하여 참조 문자열과 접근 service URL을 반환한다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	required=true,
    	description="해석할 참조의 JSON 표현식",
		content = {
			@Content(schema = @Schema(implementation=String.class), mediaType="application/json")
		}
	)
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200",
    				description="성공. 'referenceString'과 'serviceUrl'을 담은 JSON 객체가 반환된다.",
					content = {
						@Content(schema = @Schema(implementation=String.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="400", description="참조 JSON이 올바르지 않거나 지원하지 않는 참조 형식인 경우.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					}),
    	@ApiResponse(responseCode="404",
					description="참조가 가리키는 MDTInstance가 등록되어 있지 않습니다.",
					content = {
						@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
					})
    })
    @PostMapping("/references/$json")
    public ResponseEntity<?> parseJsonNodeReference(@RequestBody String refJsonString) throws IOException {
    	ElementReference ref;
    	try {
    		ref = ElementReferences.parseJsonString(refJsonString);
    	}
    	catch ( JsonProcessingException e ) {
    		// JSON 문법 오류, @type 누락·미등록 타입, 필드 누락·타입 오류(JacksonDeserializationException)는
    		// 요청 본문의 오류다. 원래 메시지만 쓴다(위치 정보에 본문 일부가 붙지 않도록).
    		String msg = String.format("invalid reference JSON: %s", e.getOriginalMessage());
    		return ResponseEntity.badRequest().body(RESTfulErrorEntity.ofMessage(msg));
    	}
    	if ( ref instanceof SubmodelBasedElementReference smbeRef ) {
    		smbeRef.activate(m_instanceManager);
    		Map<String,String> respMsg = Map.of("referenceString", smbeRef.toStringExpr(),
    											"serviceUrl", smbeRef.getServiceUrl());
    		String respJson = MDTModelSerDe.MAPPER.writeValueAsString(respMsg);
			return jsonOk(respJson);
		}
		else {
			String msg = String.format("Unexpected reference type: %s", ref.getClass().getName());
			return ResponseEntity.badRequest().body(RESTfulErrorEntity.ofMessage(msg));
		}
    }
    
    private List<MDTInstance> findInstances(@Nullable String filter) {
    	return ( filter != null ) ? m_instanceManager.getInstanceAllByFilter(filter)
    								: m_instanceManager.getInstanceAll();
    }
    
    private static MDTModel toMDTModel(JpaInstance instance) {
    	MDTModel model = new MDTModel(instance.getInstanceDescriptor());
    	model.setSubmodels(instance.getMDTSubmodelDescriptorAll());
    	model.setParameters(instance.getMDTParameterDescriptorAll());
    	model.setOperations(instance.getMDTOperationDescriptorAll());
    	model.setTwinComposition(instance.getMDTTwinCompositionDescriptor());
    	return model;
    }
    
    public interface ReferenceHandler {
    	public ResponseEntity<?> handle(MDTElementReference ref) throws Exception;
    	default public ResponseEntity<?> handle(DefaultSubmodelReference ref) throws Exception {
			String msg = String.format("SubmodelReference is not supported for value: expr=%s", ref.toStringExpr());
			return ResponseEntity.badRequest().body(RESTfulErrorEntity.ofMessage(msg));
    	}
    };
    private ResponseEntity<?> handleReference(String refString, ReferenceHandler handler) throws Exception {
		Object ref = parseExpression(refString);
		if ( ref instanceof MDTElementReference elmRef ) {
			elmRef.activate(m_instanceManager);
			return handler.handle(elmRef);
		}
		else if ( ref instanceof DefaultSubmodelReference smRef ) {
			smRef.activate(m_instanceManager);
			return handler.handle(smRef);
		}
		else {
			String msg = String.format("Unexpected reference expression: ref=%s", refString);
			return ResponseEntity.badRequest().body(RESTfulErrorEntity.ofMessage(msg));
		}
    }
    
    private Object parseExpression(String refString) {
		MDTExpression expr;
		try {
			expr = MDTExpressionParser.parseExpr(refString);
		}
		catch ( MDTParserException e ) {
			// 참조 표현식 문법 오류는 요청의 오류다(400). 2026-11-05 이전에는 500이었다.
			throw new IllegalArgumentException("invalid reference expression: '" + refString + "', cause="
												+ e.getMessage(), e);
		}
		if ( expr instanceof MDTElementReferenceExpr refExpr ) {
			try {
				MDTElementReference ref = refExpr.evaluate();
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
			return smRef;
		}
		else {
			String msg = String.format("Unexpected reference expression: expr=%s", refString);
			throw new IllegalArgumentException(msg);
		}
    }
    
    /**
     * 업로드된 bundle zip을 {@code workDir}에 저장하고 {@code workDir/bundle}에 압축을 푼다.
     * <p>
     * {@code workDir}은 요청마다 새로 만든 디렉터리라, 같은 id로 동시에 요청해도 서로의 파일을 건드리지 않는다.
     * 업로드 파일 이름은 쓰지 않는다. 2026-11-05 이전에는 {@code <bundlesDir>/<원래 파일 이름>}과
     * {@code <bundlesDir>/<id>}를 요청끼리 공유해, 동시 요청·중복 요청이 진행 중인 요청의 bundle을 지웠고, 압축 해제가
     * 실패하면 zip이 남았으며, 파일 이름이 없으면 NPE가 났다.
     *
     * @param workDir		요청 전용 작업 디렉터리.
     * @param zippedBundle	업로드된 bundle zip.
     * @return	압축을 푼 bundle 디렉터리.
     */
    private File buildBundle(File workDir, MultipartFile zippedBundle) throws IOException {
    	File zipFile = new File(workDir, "bundle.zip");
		try ( InputStream is = zippedBundle.getInputStream() ) {
			IOUtils.write(zipFile, is);
		}
		File bundleDir = new File(workDir, "bundle");
		try {
			new ZipFile(zipFile.toPath()).unzip(bundleDir.toPath());
		}
		catch ( java.util.zip.ZipException e ) {
			// 올바른 zip이 아닌 업로드는 요청의 오류다(400).
			throw new IllegalArgumentException("invalid bundle zip: " + e.getMessage(), e);
		}
		return bundleDir;
    }
}
