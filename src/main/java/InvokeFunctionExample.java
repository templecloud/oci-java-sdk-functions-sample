import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.CreateSubnetDetails;
import com.oracle.bmc.core.model.CreateVcnDetails;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.CreateSubnetRequest;
import com.oracle.bmc.core.requests.CreateVcnRequest;
import com.oracle.bmc.core.requests.DeleteSubnetRequest;
import com.oracle.bmc.core.requests.DeleteVcnRequest;
import com.oracle.bmc.core.requests.GetSubnetRequest;
import com.oracle.bmc.core.requests.GetVcnRequest;
import com.oracle.bmc.core.requests.ListSubnetsRequest;
import com.oracle.bmc.core.requests.ListVcnsRequest;
import com.oracle.bmc.core.responses.CreateSubnetResponse;
import com.oracle.bmc.core.responses.CreateVcnResponse;
import com.oracle.bmc.core.responses.GetSubnetResponse;
import com.oracle.bmc.core.responses.GetVcnResponse;
import com.oracle.bmc.core.responses.ListSubnetsResponse;
import com.oracle.bmc.core.responses.ListVcnsResponse;
import com.oracle.bmc.functions.FunctionsInvokeClient;
import com.oracle.bmc.functions.FunctionsManagementClient;
import com.oracle.bmc.functions.model.Application;
import com.oracle.bmc.functions.model.ApplicationSummary;
import com.oracle.bmc.functions.model.CreateApplicationDetails;
import com.oracle.bmc.functions.model.CreateFunctionDetails;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.model.FunctionSummary;
import com.oracle.bmc.functions.requests.CreateApplicationRequest;
import com.oracle.bmc.functions.requests.CreateFunctionRequest;
import com.oracle.bmc.functions.requests.DeleteApplicationRequest;
import com.oracle.bmc.functions.requests.DeleteFunctionRequest;
import com.oracle.bmc.functions.requests.GetApplicationRequest;
import com.oracle.bmc.functions.requests.GetFunctionRequest;
import com.oracle.bmc.functions.requests.InvokeFunctionRequest;
import com.oracle.bmc.functions.requests.ListApplicationsRequest;
import com.oracle.bmc.functions.requests.ListFunctionsRequest;
import com.oracle.bmc.functions.responses.CreateApplicationResponse;
import com.oracle.bmc.functions.responses.CreateFunctionResponse;
import com.oracle.bmc.functions.responses.GetApplicationResponse;
import com.oracle.bmc.functions.responses.GetFunctionResponse;
import com.oracle.bmc.functions.responses.InvokeFunctionResponse;
import com.oracle.bmc.functions.responses.ListApplicationsResponse;
import com.oracle.bmc.functions.responses.ListFunctionsResponse;
import com.oracle.bmc.identity.Identity;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.AvailabilityDomain;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.identity.responses.ListAvailabilityDomainsResponse;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.util.StreamUtils;

import org.apache.commons.io.IOUtils;

public class InvokeFunctionExample {

    final static Region DEFAULT_REGION = Region.US_PHOENIX_1;

    final static String SETUP = "setup";
    final static String INVOKE = "invoke";
    final static String TEARDOWN = "teardown";

    /**
     * This is a basic example of how to register and invoke a serverless Function
     * on OCI using the Java SDK.
     * 
     * The example has some pre-requisites. In particular you will need to create a
     * Function and publish it to OCIR. The best way to do this is with the 'Fn
     * CLI':
     * 
     * 1. Install Fn CLI : https://github.com/fnproject/cli 
     * 
     * 2. Create Function -
     *  Quick Guide : https://github.com/fnproject/fn/blob/master/README.md
     * 
     * ---
     * 
     * This sample will do following things:
     * 
     * 1. Create VCN and subnets - Provide an endpoint on which your function can be
     * invoke. 
     * 
     * 2. Create Application and Function - Register and configure your
     * function. 
     * 
     * 3. Invoke Function - How your function can be invoked. 
     * 
     * 4. Clean-up - Tidy up the resources created above.
     * 
     * > NB:  To simplify things, this example is hardcoded to the 'us-phoenix-1' OCI
     *        region.
     * 
     * > NB: Currently, after invoking a function we must wait 30 minutes before 
     *       clearing down any supporting Subnets and VCN.
     * 
     * @param args to control setting up, invoking, and, cleaning up function resources.
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {

        // Handle commands: {setup. invoke, teardown}
        final Set<String> commands = new TreeSet<String>(Arrays.asList(args));

        // All resources will be prefixed with this name.
        final String name = "oci-java-sdk-function-example";

        // The default region to use.
        final Region region = DEFAULT_REGION;

        // We need a target compartment.
        final String compartmentId = System.getenv("COMPARTMENT_ID");

        // We need an accessible image in the pheonix region to invoke. 
        // e.g. phx.ocir.io/tenancy-name/registry/imagename:version
        final String image = System.getenv("OCIR_FN_IMAGE");

        if (compartmentId == null) {
            throw new Exception(
                "Please ensure you have set the mandatory environment variables - COMPARTMENT_ID, OCIR_FN_IMAGE");
        }

        // Depending on the image chosen a payload can be specified.
        final String payload = (System.getenv("FN_PAYLOAD") != null) ? System.getenv("FN_PAYLOAD") : "";

        // Configure Auth
        final String configurationFilePath = "~/.oci/config";
        final String profile = "DEFAULT";
        final AuthenticationDetailsProvider provider =
            new ConfigFileAuthenticationDetailsProvider(configurationFilePath, profile);
        
        try {
            if (commands.contains(SETUP)) {
                setupResources(provider, region, compartmentId, name, image);
            }

            if (commands.contains(INVOKE)) {
                invokeFunction(provider, region, compartmentId, name, payload);
            }

            if (commands.contains(TEARDOWN)) {
                teardownResources(provider, region, compartmentId, name);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error: " + e);
        }
    }

    /**
     * Create all the OCI and Fn resources required to invoke a function.
     *
     * @param provider the OCI credentials provider. 
     * @param region the OCI region in which to create the required resources.
     * @param compartmentId the compartment in which to created the required resources.
     * @param name a name prefix to easilly identifty the resources.
     * @param image a valid OCIR image for the function.
     * @throws Exception
     */
    public static void setupResources(
            final AuthenticationDetailsProvider provider, 
            final Region region, 
            final String compartmentId,
            final String name,
            final String image
        ) throws Exception {

        final Identity identityClient = new IdentityClient(provider);
        identityClient.setRegion(region);

        final VirtualNetworkClient vcnClient = new VirtualNetworkClient(provider);
        vcnClient.setRegion(region);

        final FunctionsManagementClient fnManagementClient = new FunctionsManagementClient(provider);
        fnManagementClient.setRegion(region);

        final FunctionsInvokeClient fnInvokeClient = new FunctionsInvokeClient(provider);
        fnManagementClient.setRegion(region);

        try {
            // 1. A list of AvailabiityDomains are required to determine where to host each subnet.
            final List<AvailabilityDomain> availabilityDomains = getAvailabilityDomains(identityClient, compartmentId);
            final AvailabilityDomain ad = availabilityDomains.get(0);
            System.out.printf("Using availability domain: " + ad.getName() + "\n");

            // 2. A VCN is required to host subnets.
            final String vcnDisplayName = vcnName(name);
            final String vcnCidrBlock = "10.0.0.0/16";
            final Vcn vcn = createVcn(vcnClient, compartmentId, vcnDisplayName, vcnCidrBlock);
            System.out.println("Created VCN: " + vcn.getDisplayName());

            // 3. A subnet is required to expose and be able invoke the function.
            // In multiple AD regions, subnets can be created in multiple ADs to provide redundency.
            final String subnetDisplayName = subnetName(name);
            final String subnetCidrBlock = "10.0.0.0/24";
            final Subnet subnet = createSubnet(vcnClient, compartmentId, vcn.getId(), subnetDisplayName, ad.getName(), subnetCidrBlock);
            System.out.println("Created VCN subnet: " + subnet.getDisplayName());

            // 4. Create an Application to host and manage the function.
            final String appDisplayName = applicationName(name);
            final List<String> subnetIds = new ArrayList<>();
            subnetIds.add(subnet.getId());
            final Application app = createApplication(fnManagementClient, compartmentId, appDisplayName, subnetIds);
            System.out.println("Created Application: " + app.getDisplayName());

            // 5. Create a Function, set its execution image and limits.
            final String fnDisplayName = functionName(name);
            final long memoryInMBs = 128L;
            final int timeoutInSeconds = 30;
            final Function fn = createFunction(fnManagementClient, app.getId(), fnDisplayName, image, memoryInMBs, timeoutInSeconds);
            System.out.println("Created Function: " + fn.getDisplayName());

        } finally {
            fnInvokeClient.close();     
            fnManagementClient.close();
            vcnClient.close();
            identityClient.close();
        }
    }

    /**
     * Create all the OCI and Fn resources required to invoke a function.
     *
     * @param provider the OCI credentials provider. 
     * @param region the OCI region in which to create the required resources.
     * @param compartmentId the compartment in which to created the required resources.
     * @param name a name prefix to easilly identifty the resources.
     * @param image a valid OCIR image for the function.
     * @throws Exception 
     */
    public static void invokeFunction(
            final AuthenticationDetailsProvider provider, 
            final Region region, 
            final String compartmentId,
            final String name,
            final String payload
        ) throws Exception {

        final FunctionsManagementClient fnManagementClient = new FunctionsManagementClient(provider);
        fnManagementClient.setRegion(region);

        final FunctionsInvokeClient fnInvokeClient = new FunctionsInvokeClient(provider);
        fnManagementClient.setRegion(region);

        try {
            // 6. Invoke the function!
            final String appName = applicationName(name);
            final String fnName = functionName(name);
            final FunctionSummary fn = 
                getUniqueFunctionByName(fnManagementClient, compartmentId, appName, fnName);

            final String response = invokeFunction(fnInvokeClient, fn, payload);
            if (response != null) {
                System.out.println("Response from function:  " + response);
            }
            
        } finally {
            fnInvokeClient.close();     
            fnManagementClient.close();
        }
    }

    /**
     * Create all the OCI and Fn resources required to invoke a function.
     * 
     * NB: Resources can only be removed 30 minutes after the last Function invocation.
     *
     * @param provider the OCI credentials provider. 
     * @param region the OCI region in which to create the required resources.
     * @param compartmentId the compartment in which to created the required resources.
     * @param name a name prefix to easilly identifty the resources.
     * @param image a valid OCIR image for the function.
     * @throws Exception 
     */
    public static void teardownResources(
            final AuthenticationDetailsProvider provider, 
            final Region region, 
            final String compartmentId,
            final String name
        ) throws Exception {

        final Identity identityClient = new IdentityClient(provider);
        identityClient.setRegion(region);

        final VirtualNetworkClient vcnClient = new VirtualNetworkClient(provider);
        vcnClient.setRegion(region);

        final FunctionsManagementClient fnManagementClient = new FunctionsManagementClient(provider);
        fnManagementClient.setRegion(region);

        final FunctionsInvokeClient fnInvokeClient = new FunctionsInvokeClient(provider);
        fnManagementClient.setRegion(region);

        try {
            System.out.println("Cleaning up");

            final String vcnName = vcnName(name);
            final String subnetName = subnetName(name);
            final String appName = applicationName(name);
            final String fnName = functionName(name);

            final Vcn vcn = getUniqueVcnByName(vcnClient, compartmentId, vcnName);
            final Subnet subnet = 
                getUniqueSubnetByName(vcnClient, compartmentId, vcn.getId(), subnetName);
            final ApplicationSummary application = 
                getUniqueApplicationByName(fnManagementClient, compartmentId, appName);
            final FunctionSummary fn = 
                getUniqueFunctionByName(fnManagementClient, application.getId(), fnName);

            if (fn != null) {
                deleteFunction(fnManagementClient, fn.getId());
                System.out.println("Deleted function: " + fn.getDisplayName());
            }

            if (application != null) {
                deleteApplication(fnManagementClient, application.getId());
                System.out.println("Deleted application: " + application.getDisplayName());
            }

            if (subnet != null) {
                deleteSubnet(vcnClient, subnet.getId());
                System.out.println("Deleted subnet: " + subnet.getDisplayName());
            }

            if (vcn != null) {
                deleteVcn(vcnClient, vcn);
                System.out.println("Deleted VCN: " + vcn.getDisplayName());
            }

        } finally {
            fnInvokeClient.close();     
            fnManagementClient.close();
            vcnClient.close();
            identityClient.close();
        }
    }

    // === OCI Identity Helpers ===

    /**
     * List the AvailabilityDomains.
     *
     * @param identityClient the service client to use to fetch the AvailabilityDomains. 
     * @param compartmentId the OCID of the compartment to check.
     * @return the list of AvailabilityDomains.
     * @throws Exception
     */
    public static List<AvailabilityDomain> getAvailabilityDomains(
            final Identity identityClient, 
            final String compartmentId
        ) throws Exception {

        final ListAvailabilityDomainsResponse listAvailabilityDomainsResponse =
            identityClient.listAvailabilityDomains(
                    ListAvailabilityDomainsRequest.builder()
                        .compartmentId(compartmentId)
                        .build());

        identityClient.close();

        return listAvailabilityDomainsResponse.getItems();
    }

    // === OCI VCN Helpers ===

    /**
     * Creates a VCN and waits for it to become available to use.
     *
     * @param vcnClient the service client to use to create the VCN.
     * @param compartmentId the OCID of the compartment where the VCN will be created.
     * @param availabilityDomain the availability domain where the subnet will be created.
     * @param cidrBlock the CidrBlock allocated for the VCN.
     * @return the created VCN.
     * @throws Exception
     */
    private static Vcn createVcn(
            final VirtualNetworkClient vcnClient, 
            final String compartmentId,
            final String displayName,
            final String cidrBlock
        ) throws Exception {

        final CreateVcnResponse createVcnResponse =
            vcnClient.createVcn(
                CreateVcnRequest.builder()
                    .createVcnDetails(
                        CreateVcnDetails.builder()
                            .compartmentId(compartmentId)
                            .displayName(displayName)
                            .cidrBlock(cidrBlock)
                            .build())
                    .build());

        final GetVcnResponse getVcnResponse =
            vcnClient.getWaiters()
                .forVcn(
                    GetVcnRequest.builder()
                        .vcnId(createVcnResponse.getVcn().getId())
                        .build(),
                    Vcn.LifecycleState.Available)
                .execute();

        return getVcnResponse.getVcn();
    }

    /**
     * Gets VCN info of a single uniquely named VCN in the specified compartment.
     * 
     * @param vcnClient the service client to use to query the VCN.
     * @param compartmentId of the VCN.
     * @param vcnDisplayName of the VCN.
     * @return the VCN.
     * @throws Exception 
     */
    public static Vcn getUniqueVcnByName(
            final VirtualNetworkClient vcnClient, 
            final String compartmentId, 
            final String vcnDisplayName
        ) throws Exception {

        // Find the application in a specific compartment
        final ListVcnsRequest listVcnsRequest = 
            ListVcnsRequest.builder()
                .compartmentId(compartmentId)
                .displayName(vcnDisplayName)
                .build();

        final ListVcnsResponse listVcnsResponse = vcnClient.listVcns(listVcnsRequest);

        if (listVcnsResponse.getItems().size() != 1) {
            throw new Exception(
                    "Could not find unique VCN with name " 
                    + vcnDisplayName + " in compartment " + compartmentId);
        }

        return listVcnsResponse.getItems().get(0);
    }

    /**
     * Deletes a VCN and waits for it to be deleted.
     *
     * @param vcnClient the service client to use to delete the VCN.
     * @param vcn the VCN to delete.
     * @throws Exception
     */
    private static void deleteVcn(final VirtualNetworkClient vcnClient, final Vcn vcn)
            throws Exception {

        vcnClient.deleteVcn(DeleteVcnRequest.builder().vcnId(vcn.getId()).build());

        vcnClient.getWaiters()
            .forVcn(
                GetVcnRequest.builder().vcnId(vcn.getId()).build(),
                Vcn.LifecycleState.Terminated)
            .execute();
    }

    // === OCI Subnet Helpers ===

    /**
     * Creates a subnet in a VCN and waits for the subnet to become available to use.
     *
     * @param vcnClient the service client to use to create the subnet.
     * @param compartmentId the OCID of the compartment which owns the VCN.
     * @param vcnId the ID of the VCN which will own the subnet.
     * @param displayName the display name of the subnet.
     * @param availabilityDomain the availability domain where the subnet will be created.
     * @param subnetCidrBlock the subnet CidrBlock allocated from the parent VCN range.
     * @return the created subnet.
     * @throws Exception
     */
    private static Subnet createSubnet(
            final VirtualNetworkClient vcnClient,
            final String compartmentId,
            final String vcnId,
            final String displayName,
            final String availabilityDomainName,
            final String subnetCidrBlock
        ) throws Exception {

        // Create the subnet
        final CreateSubnetResponse createSubnetResponse =
            vcnClient.createSubnet(
                CreateSubnetRequest.builder()
                    .createSubnetDetails(
                        CreateSubnetDetails.builder()
                            .availabilityDomain(availabilityDomainName)
                            .compartmentId(compartmentId)
                            .displayName(displayName)
                            .cidrBlock(subnetCidrBlock)
                            .vcnId(vcnId)
                            .build())
                    .build());

        // Wait for the subnet to be active
        final GetSubnetResponse getSubnetResponse =
            vcnClient
                .getWaiters()
                .forSubnet(
                    GetSubnetRequest.builder()
                        .subnetId(createSubnetResponse.getSubnet().getId())
                        .build(),
                    Subnet.LifecycleState.Available)
                .execute();

        return getSubnetResponse.getSubnet();
    }

    /**
     * Gets VCN info of a single uniquely named VCN in the specified compartment.
     * 
     * @param vcnClient the service client to use to query the Subnet.
     * @param compartmentId of the Subnet.
     * @param vcnId of the Subnet.
     * @param subnetDisplayName of the Subnet.
     * @return the Subnet.
     * @throws Exception 
     */
    public static Subnet getUniqueSubnetByName(
            final VirtualNetworkClient vcnClient, 
            final String compartmentId, 
            final String vcnId,
            final String subnetDisplayName
        ) throws Exception {

        // Find the application in a specific compartment
        final ListSubnetsRequest listSubnetsRequest = 
            ListSubnetsRequest.builder()
                .compartmentId(compartmentId)
                .vcnId(vcnId)
                .displayName(subnetDisplayName)
                .build();

        final ListSubnetsResponse listSubnetsResponse = vcnClient.listSubnets(listSubnetsRequest);

        if (listSubnetsResponse.getItems().size() != 1) {
            throw new Exception(
                    "Could not find unique subnet with name " 
                    + subnetDisplayName + " in compartment " + compartmentId);
        }

        return listSubnetsResponse.getItems().get(0);
    }


    /**
     * Deletes a subnet and waits for it to be deleted.
     *
     * @param vcnClient the service client to use to delete the subnet.
     * @param subnetId the subnet to delete.
     * @throws Exception
     */
    private static void deleteSubnet(
            final VirtualNetworkClient vcnClient, 
            final String subnetId
        ) throws Exception {

        final int DELETE_SUBNET_ATTEMPTS = 5;
        /*
         * Sometimes we can't delete the subnet straight after a mount target has been deleted
         * as network resources still need to clear. If we get a conflict, try a few times before
         * bailing out.
         */
        int numAttempts = 0;
        while (true) {
            try {
                vcnClient.deleteSubnet(
                    DeleteSubnetRequest.builder().subnetId(subnetId).build());
                break;
            } catch (BmcException e) {
                numAttempts++;
                if (e.getStatusCode() == 409 && numAttempts < DELETE_SUBNET_ATTEMPTS) {
                    Thread.sleep(10000L);
                } else {
                    throw e;
                }
            }
        }
        // Wait for 'Terminated' state.
        vcnClient.getWaiters()
            .forSubnet(
                GetSubnetRequest.builder().subnetId(subnetId).build(),
                Subnet.LifecycleState.Terminated)
            .execute();
    }

    // === OCI Application Helpers ===

    /**
     * Creates an Application and waits for the it to become available to use.
     *
     * @param fnManagementClient the service client to use to create the application.
     * @param compartmentId the OCID of the compartment which owns the Application.
     * @param displayName the availability domain where the subnet will be created.
     * @param subnetIds a List of subnets (in different ADs) that will expose the function.
     * @return the created application.
     * @throws Exception if there is an error waiting on the application to become available to use.
     */
    private static Application createApplication(
            final FunctionsManagementClient fnManagementClient,
            final String compartmentId,
            final String displayName,
            final List<String> subnetIds
        ) throws Exception {

        // Create a new Application.
        final CreateApplicationResponse createApplicationResponse =
            fnManagementClient.createApplication(
                CreateApplicationRequest.builder()
                    .createApplicationDetails(
                        CreateApplicationDetails.builder()
                            .compartmentId(compartmentId)
                            .displayName(displayName)
                            .subnetIds(subnetIds)
                            .build())
                    .build());

        // Wait for Application to be in 'Active' state.
        final GetApplicationResponse getApplicationResponse =
            fnManagementClient
                .getWaiters()
                .forApplication(
                    GetApplicationRequest.builder()
                        .applicationId(createApplicationResponse.getApplication().getId())
                        .build(),
                    Application.LifecycleState.Active)
                .execute();

        return getApplicationResponse.getApplication();
    }

    /**
     * Gets the Application info of a single uniquely named Application in the specified compartment.
     * 
     * @param fnManagementClient the service client to use to get the Application information.
     * @param compartmentId of the application.
     * @param applicationDisplayName of the application.
     * @return the ApplicationSummary.
     * @throws Exception 
     */
    public static ApplicationSummary getUniqueApplicationByName(
            final FunctionsManagementClient fnManagementClient,
            final String compartmentId, 
            final String applicationDisplayName
        ) throws Exception {

        // Find the application in a specific compartment
        final ListApplicationsRequest listApplicationsRequest = 
            ListApplicationsRequest.builder()
                .displayName(applicationDisplayName)
                .compartmentId(compartmentId)
                .build();

        final ListApplicationsResponse resp = 
            fnManagementClient.listApplications(listApplicationsRequest);

        if (resp.getItems().size() != 1) {
            throw new Exception(
                    "Could not find unique application with name " 
                    + applicationDisplayName + " in compartment " + compartmentId);
        }

        final ApplicationSummary application = resp.getItems().get(0);
        return application;
    }

    /**
     * Deletes an Application and waits for it to be deleted.
     *
     * @param fnManagementClient the service client to use to delete the Application.
     * @param applicationId the Application to delete.
     * @throws Exception if there is an error waiting on the Application to be deleted.
     */
    private static void deleteApplication(
            final FunctionsManagementClient fnManagementClient,
            final String applicationId
        ) throws Exception {

        // Delete the specified Application
        fnManagementClient.deleteApplication(DeleteApplicationRequest.builder().applicationId(applicationId).build());

        // Wait for the 'Deleted' status.
        fnManagementClient.getWaiters()
            .forApplication(
                GetApplicationRequest.builder().applicationId(applicationId).build(),
                Application.LifecycleState.Deleted)
            .execute();
    }

    // === OCI Function Helpers ===

    /**
     * Creates a Function and waits for the it to become available to use.
     *
     * @param fnManagementClient the service client to use to create the application
     * @param compartmentId the OCID of the compartment which owns the Application.
     * @param displayName the availability domain where the subnet will be created.
     * @param image an accessible OCIR image implementing the function to be executed.
     * @param memoryInMBs the maximum ammount of memory available (128, 256, 512, 1024) to the function in MB.
     * @param timeoutInSeconds the maximum ammout of time a function can execute (30 - 120) in seconds.
     * @return the created Function.
     * @throws Exception
     */
    private static Function createFunction(
            final FunctionsManagementClient fnManagementClient,
            final String applicationId,
            final String displayName,
            final String image,
            final long memoryInMBs,
            final int timeoutInSeconds
        ) throws Exception {

        // Create a new Function.
        final CreateFunctionResponse createFunctionResponse =
            fnManagementClient.createFunction(
                CreateFunctionRequest.builder()
                    .createFunctionDetails(
                        CreateFunctionDetails.builder()
                            .applicationId(applicationId)
                            .displayName(displayName)
                            .image(image)
                            .memoryInMBs(memoryInMBs)
                            .timeoutInSeconds(timeoutInSeconds)
                            .build())
                    .build());

        // Wait for Function to be in 'Active' state.
        final GetFunctionResponse getFunctionResponse =
            fnManagementClient
                .getWaiters()
                .forFunction(
                    GetFunctionRequest.builder()
                        .functionId(createFunctionResponse.getFunction().getId())
                        .build(),
                    Function.LifecycleState.Active)
                .execute();

        return getFunctionResponse.getFunction();
    }

    /**
     * Gets Function information. This is an expensive operation and the results should be cached.
     *
     * @param fnManagementClient the service client to use to get the Function information.
     * @param compartmentId of the application and function.
     * @param applicationDisplayName of the application.
     * @param functionDisplayName of the function.
     * @return the FunctionSummary.
     * @throws Exception
     */
    public static FunctionSummary getUniqueFunctionByName(
            final FunctionsManagementClient fnManagementClient,
            final String compartmentId,
            final String applicationDisplayName, 
            final String functionDisplayName
        ) throws Exception {
        final ApplicationSummary application = 
            getUniqueApplicationByName(fnManagementClient, compartmentId, applicationDisplayName);
        return getUniqueFunctionByName(fnManagementClient, application.getId(), functionDisplayName);
    }

    /**
     * Gets Function information. This is an expensive operation and the results should be cached.
     * 
     * @param fnManagementClient the service client to use to get the Function information.
     * @param appliapplicationIdation of the function to find.
     * @param functionDisplayName of the function to find.
     * @return the FunctionSummary.
     * @throws Exception
     */
    public static FunctionSummary getUniqueFunctionByName(
            final FunctionsManagementClient fnManagementClient,
            final String applicationId, 
            final String functionDisplayName
        ) throws Exception {

        final ListFunctionsRequest listFunctionsRequest = 
            ListFunctionsRequest.builder()
                .applicationId(applicationId)
                .displayName(functionDisplayName)
                .build();

        final ListFunctionsResponse listFunctionsResponse = 
            fnManagementClient.listFunctions(listFunctionsRequest);

        if (listFunctionsResponse.getItems().size() != 1) {
            throw new Exception("Could not find function with name " 
                + functionDisplayName + " in application " + applicationId);
        }

        return listFunctionsResponse.getItems().get(0);
    }

    /**
     * Deletes a Function and waits for it to be deleted.
     *
     * @param fnManagementClient the service client to use to delete the Function.
     * @param functionId the Function to delete.
     * @throws Exception
     */
    private static void deleteFunction(
            final FunctionsManagementClient fnManagementClient,
            final String functionId
        ) throws Exception {

        // Delete the specified Function
        fnManagementClient.deleteFunction(DeleteFunctionRequest.builder().functionId(functionId).build());

        // Wait for the 'Deleted' status.
        fnManagementClient.getWaiters()
            .forFunction(
                GetFunctionRequest.builder().functionId(functionId).build(),
                Function.LifecycleState.Deleted)
            .execute();
    }

    /**
     * Invokes a function.
     * 
     * @param fnInvokedClient the service client to use to delete the Function.
     * @param function the Function to invoke.
     * @param payload the payload to pass to the function.
     * 
     * @throws Exception if there is an error when invoking the function.
     */
    private static String invokeFunction(
            final FunctionsInvokeClient fnInvokeClient,
            final FunctionSummary fn,
            final String payload
        ) throws Exception {
        String response;
        try {
            System.err.println("Invoking function endpoint - " + fn.getInvokeEndpoint());

            // Configure the client to use the assigned function endpoint.
            fnInvokeClient.setEndpoint(fn.getInvokeEndpoint());
            final InvokeFunctionRequest invokeFunctionRequest = 
                InvokeFunctionRequest.builder()
                    .functionId(fn.getId())
                    .invokeFunctionBody(
                        StreamUtils.createByteArrayInputStream(
                            payload.getBytes()))
                    .build();

            // Invoke the function!
            final InvokeFunctionResponse invokeFunctionResponse = 
                fnInvokeClient.invokeFunction(invokeFunctionRequest);

            // Handle the response.
            response = IOUtils.toString(
                invokeFunctionResponse.getInputStream(), StandardCharsets.UTF_8);

        } catch (final Exception e) {
            e.printStackTrace();
            System.err.println("Failed to invoke function: " + e);
            throw e;
        }

        return response;
    }

    // === Utility Helpers ===

    private static String vcnName(final String name) {
        return name + "-vcn";
    }

    private static String subnetName(final String name) {
        return name + "-subnet";
    }

    private static String applicationName(final String name) {
        return  name + "-app";
    }

    private static String functionName(final String name) {
        return name + "-fn";
    }

}

