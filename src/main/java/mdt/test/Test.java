package mdt.test;

import io.argoproj.workflow.ApiClient;
import io.argoproj.workflow.Configuration;
import io.argoproj.workflow.apis.WorkflowServiceApi;
import io.argoproj.workflow.models.IoArgoprojWorkflowV1alpha1WorkflowList;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class Test {
	public static void main(String... args) throws Exception {
		ApiClient defaultClient = Configuration.getDefaultApiClient();
//		defaultClient.setVerifyingSsl(false);
		defaultClient.setBasePath("https://argo.etri.dev");
		
		WorkflowServiceApi apiInstance = new WorkflowServiceApi(defaultClient);
		String namespace = "argo";
		String name = "sample-workflow-6-p45fg";
		String getOptionsResourceVersion = "argoproj.io/v1alpha1";
		String fields = "";
		
		try { 
//			IoArgoprojWorkflowV1alpha1Workflow result = apiInstance.workflowServiceGetWorkflow(namespace, name,
//																	getOptionsResourceVersion, fields);
//			System.out.println(result);
			IoArgoprojWorkflowV1alpha1WorkflowList result
				= apiInstance.workflowServiceListWorkflows(namespace, "", "", false, false, "", "",
						"", "", "", "");
			System.out.println(result);
		}
		catch ( Exception e ) {
			e.printStackTrace();
		}
	}
}
