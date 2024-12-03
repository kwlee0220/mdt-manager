package mdt.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import utils.async.Executions;

import mdt.instance.AbstractInstanceManager;
import mdt.instance.JpaInstance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
@RestController
@RequestMapping(value={""})
public class MDTManagerController implements InitializingBean {
	private final Logger s_logger = LoggerFactory.getLogger(MDTManagerController.class);
	
	@Autowired AbstractInstanceManager<? extends JpaInstance> m_instanceManager;

	@Override
	public void afterPropertiesSet() throws Exception {
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
	}

    @Operation(summary = "MDTManager의 동작 여부를 확인한다.")
    @Parameters()
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
    				content = {@Content(mediaType = "text/plain", schema = @Schema(type = "string"))}
    	)
    })
    @GetMapping("/health")
    @ResponseStatus(HttpStatus.OK)
    public String ping() {
    	return "ok";
    }
    
//    @Operation(summary = "MDTManager를 shutdown 시킨다.")
//    @Parameters()
//    @DeleteMapping("/shutdown")
//    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void shutdown() {
    	if ( s_logger.isInfoEnabled() ) {
    		s_logger.info("shutting down MDTManager...");
    	}
    	
    	Executions.runAsync(() -> {
    		Thread.sleep(1000);
    		m_instanceManager.shutdown();
    		System.exit(0);
    	});
    }
}
