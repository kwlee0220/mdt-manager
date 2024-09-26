package mdt.workflow;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import utils.func.Try;
import utils.stream.FStream;

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
import mdt.instance.jpa.JpaProcessor;
import mdt.model.AASUtils;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.workflow.descriptor.TaskTemplateDescriptor;
import mdt.model.workflow.descriptor.WorkflowDescriptor;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
@RestController
@RequestMapping("/workflow-manager")
public class MDTWorkflowManagerController extends MDTController<WorkflowDescriptor>
											implements InitializingBean {
	@SuppressWarnings("unused")
	private final Logger s_logger = LoggerFactory.getLogger(MDTWorkflowManagerController.class);
	
	@Autowired EntityManagerFactory m_emFact;
	private JpaProcessor m_processor;

	@Override
	public void afterPropertiesSet() throws Exception {
		m_processor = new JpaProcessor(m_emFact);
	}

    @Operation(summary = "식별자에 해당하는 워크플로우 등록정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description = "검색할 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = WorkflowDescriptor.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404", description = "식별자에 해당하는 워크플로우가 등록되지 않은 경우.")
    })
    @GetMapping("/descriptors/{id}")
    @ResponseStatus(HttpStatus.OK)
    public String getWorkflowDescriptor(@PathVariable("id") String id) {
    	JpaWorkflowDescriptorManager manager = new JpaWorkflowDescriptorManager();
    	return m_processor.get(manager, () -> manager.getJpaWorkflowDescriptor(id))
    						.getJsonDescriptor();
    }

    @Operation(summary = "등록된 모든 워크플로우 등록정보들을 반환한다.")
    @Parameters()
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
    		content = {
    			@Content(mediaType = "application/json",
    					array = @ArraySchema(schema=@Schema(implementation = WorkflowDescriptor.class)))
    		}
    	)
    })
    @GetMapping("/descriptors")
    @ResponseStatus(HttpStatus.OK)
    public String getWorkflowDescriptorAll() {
    	JpaWorkflowDescriptorManager manager = new JpaWorkflowDescriptorManager();
    	List<WorkflowDescriptor> wfDescList
    			= m_processor.get(manager, () -> FStream.from(manager.getWorkflowDescriptorAll())
        												.toList());
    	return AASUtils.writeJson(wfDescList);
    }

    @Operation(summary = "워크플로우 관리자에 주어진 워크플로우 등록정보를 등록시킨다.")
    @Parameters({
    	@Parameter(name = "wfDescJson", description = "Json 형식으로 작성된 워크플로우 등록 정보"),
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "201", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = WorkflowDescriptor.class),
						mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "400",
    				description = "워크플로우 등록 정보 파싱에 실패하였거나,"
    							+ "식별자에 해당하는 워크플로우 등록정보가 이미 존재합니다.")
    })
    @PostMapping({"/descriptors"})
    @ResponseStatus(HttpStatus.CREATED)
    public String addWorkflowDescriptor(@RequestBody String wfDescJson) throws ResourceAlreadyExistsException {
    	JpaWorkflowDescriptorManager manager = new JpaWorkflowDescriptorManager();
    	String wfId = m_processor.get(manager, () -> manager.addWorkflowDescriptor(wfDescJson));
    	return AASUtils.writeJson(wfId);
    }

    @Operation(summary = "식별자에 해당하는 워크플로우 등록정보를 삭제한다.")
    @Parameters({
    	@Parameter(name = "id", description = "삭제할 워크플로우 등록 정보 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "204", description = "성공"),
    	@ApiResponse(responseCode = "404",
    				description = "식별자에 해당하는 워크플로우 등록 정보가 등록되어 있지 않습니다.")
    })
    @DeleteMapping("/descriptors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeInstance(@PathVariable("id") String id) throws SerializationException {
    	JpaWorkflowDescriptorManager manager = new JpaWorkflowDescriptorManager();
    	m_processor.run(manager, () -> manager.removeWorkflowDescriptor(id));
    }
    @Operation(summary = "등록된 모든 워크플로우 등록정보를 삭제한다.")
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "204", description = "성공")
    })
    @DeleteMapping("/descriptors")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeInstanceAll() {
    	JpaWorkflowDescriptorManager manager = new JpaWorkflowDescriptorManager();
    	Try.run(() -> m_processor.run(manager, manager::removeWorkflowDescriptorAll));
    }

    @Operation(summary = "주어진 식별자에 해당하는 태스크 템플릿 등록 정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "id", description = "검색 대상 task template 식별자."),
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "201", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = TaskTemplateDescriptor.class),
						mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404",
    				description = "식별자에 해당하는 태스크 템플릿 등록정보가 존재하지 않습니다.")
    })
    @GetMapping("/builtin-tasks/{id}")
    @ResponseStatus(HttpStatus.OK)
    public String getBuiltInTaskTemplate(@PathVariable("id") String id) {
    	JpaWorkflowDescriptorManager manager = new JpaWorkflowDescriptorManager();
    	TaskTemplateDescriptor tmplt = m_processor.get(manager, () -> manager.getBuiltInTaskTemplate(id));
    	return tmplt.toJsonString();
    }
    
    @Operation(summary = "등록된 모든 태스크 템플릿 등록정보들을 반환한다.")
    @Parameters()
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
    		content = {
    			@Content(mediaType = "application/json",
    					array = @ArraySchema(schema=@Schema(implementation = TaskTemplateDescriptor.class)))
    		}
    	)
    })
    @GetMapping("/builtin-tasks")
    @ResponseStatus(HttpStatus.OK)
    public String getBuiltInTaskTemplateAll() {
    	JpaWorkflowDescriptorManager manager = new JpaWorkflowDescriptorManager();
    	return m_processor.get(manager, () -> FStream.from(manager.getBuiltInTaskTemplateAll())
    													.map(TaskTemplateDescriptor::toJsonString)
        												.join(",", "[", "]"));
    }
}
