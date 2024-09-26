package mdt.test;

import io.argoproj.workflow.ApiClient;
import io.argoproj.workflow.Configuration;
import io.argoproj.workflow.apis.WorkflowServiceApi;
import io.argoproj.workflow.auth.ApiKeyAuth;
import io.argoproj.workflow.models.IoArgoprojWorkflowV1alpha1WorkflowList;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class Test {
	public static void main(String... args) throws Exception {
		ApiClient defaultClient = Configuration.getDefaultApiClient();
		defaultClient.setBasePath("https://129.254.89.182:18080");
		
		ApiKeyAuth bearerToken = (ApiKeyAuth)defaultClient.getAuthentication("BearerToken");
		bearerToken.setApiKey(null);
		
		WorkflowServiceApi apiInstance = new WorkflowServiceApi(defaultClient);
		String namespace = "default";
		
		try {
			IoArgoprojWorkflowV1alpha1WorkflowList result
				= apiInstance.workflowServiceListWorkflows(namespace, null, null, null, null, null, null,
															null, null, null, null);
			System.out.println(result);
		}
		catch ( Exception e ) {
			e.printStackTrace();
		}
	}
}
