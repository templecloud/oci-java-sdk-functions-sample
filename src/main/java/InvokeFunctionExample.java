import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
import com.oracle.bmc.core.responses.CreateSubnetResponse;
import com.oracle.bmc.core.responses.CreateVcnResponse;
import com.oracle.bmc.core.responses.GetSubnetResponse;
import com.oracle.bmc.core.responses.GetVcnResponse;
import com.oracle.bmc.functions.FunctionsInvokeClient;
import com.oracle.bmc.functions.FunctionsManagementClient;
import com.oracle.bmc.functions.model.Application;
import com.oracle.bmc.functions.model.CreateApplicationDetails;
import com.oracle.bmc.functions.model.CreateFunctionDetails;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.requests.CreateApplicationRequest;
import com.oracle.bmc.functions.requests.CreateFunctionRequest;
import com.oracle.bmc.functions.requests.DeleteApplicationRequest;
import com.oracle.bmc.functions.requests.DeleteFunctionRequest;
import com.oracle.bmc.functions.requests.GetApplicationRequest;
import com.oracle.bmc.functions.requests.GetFunctionRequest;
import com.oracle.bmc.functions.requests.InvokeFunctionRequest;
import com.oracle.bmc.functions.responses.CreateApplicationResponse;
import com.oracle.bmc.functions.responses.CreateFunctionResponse;
import com.oracle.bmc.functions.responses.GetApplicationResponse;
import com.oracle.bmc.functions.responses.GetFunctionResponse;
import com.oracle.bmc.functions.responses.InvokeFunctionResponse;
import com.oracle.bmc.identity.Identity;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.AvailabilityDomain;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.identity.responses.ListAvailabilityDomainsResponse;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.util.StreamUtils;

import org.apache.commons.io.IOUtils;


public class InvokeFunctionExample {
    /**
     * This is a basic example of how to register and invoke a serverless Function on OCI using 
     * the Java SDK.
     * 
     * The example has some pre-requisites. In particular you will need to create a Function and 
     * publish it to OCIR. The best way to do this is with the 'Fn CLI':
     * 
     * 1. Install Fn CLI                :  https://github.com/fnproject/cli
     * 2. Create Function - Quick Guide :  https://github.com/fnproject/fn/blob/master/README.md
     * 
     * To simplify things, this example is hardcoded to the 'us-phoenix-a' OCI region.
     * 
     * This sample will do following things:
     * 
     * 1. Create VCN and subnets - Provide an endpoint on which your function can be invoke.
     * 2. Create Application and Function - Register and configure your function.
     * 3. Invoke Function - How your function can be invoked.
     * 4. Clean-up - Tidy up the resources created above..
     * 
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {

        final String displayName = "oci-java-sdk-function-example";

        // Environment
        //

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
        final String payload = System.getenv("FN_PAYLOAD");

        // Configure Auth
        //

        final String configurationFilePath = "~/.oci/config";
        final String profile = "DEFAULT";
        final AuthenticationDetailsProvider provider =
            new ConfigFileAuthenticationDetailsProvider(configurationFilePath, profile);

        // Configure Clients
        //

        final Identity identityClient = new IdentityClient(provider);
        identityClient.setRegion(Region.US_PHOENIX_1);

        final VirtualNetworkClient vcnClient = new VirtualNetworkClient(provider);
        vcnClient.setRegion(Region.US_PHOENIX_1);

        final FunctionsManagementClient fnManagementClient = new FunctionsManagementClient(provider);
        fnManagementClient.setRegion(Region.US_PHOENIX_1); // Oracle Functions currently only in phoenix

        final FunctionsInvokeClient fnInvokeClient = new FunctionsInvokeClient(provider);
        fnManagementClient.setRegion(Region.US_PHOENIX_1); // Oracle Functions currently only in phoenix

        // Execute
        //
        // 1. Create VCN and subnets.
        // 2. Create Application and Function.
        // 3. Invoke Function.
        // 4. Clean-up
        //

        Vcn vcn = null;;
        Subnet subnet = null;;
        Application app = null;
        Function fn = null;
        try {

            // 1. A list of AvailabiityDomains are required to determine where to host each subnet.
            final List<AvailabilityDomain> availabilityDomains = getAvailabilityDomains(identityClient, compartmentId);
            final AvailabilityDomain ad = availabilityDomains.get(0);
            System.out.printf("Using availability domain: " + ad.getName() + "\n");

            // 2. A VCN is required to host subnets.
            final String vcnDisplayName = displayName + "-vcn";
            final String vcnCidrBlock = "10.0.0.0/16";
            vcn = createVcn(vcnClient, compartmentId, vcnDisplayName, vcnCidrBlock);
            System.out.println("Created VCN: " + vcn.getDisplayName());

            // 3. A subnet is required to expose and be able invoke the function.
            // In multiple AD regions, subnets can be created in multiple ADs to provide redundency.
            final String subnet01DisplayName = displayName + "-01-subnet";
            final String subnet01CidrBlock = "10.0.0.0/24";
            subnet = createSubnet(vcnClient, compartmentId, vcn.getId(), subnet01DisplayName, ad.getName(), subnet01CidrBlock);
            System.out.println("Created VCN subnet: " + subnet.getDisplayName());

            // 4. Create an Application to host and manafe the function.
            final String appDisplayName = displayName + "-app"; 
            final List<String> subnetIds = new ArrayList<>();
            subnetIds.add(subnet.getId());
            app = createApplication(fnManagementClient, compartmentId, appDisplayName, subnetIds);
            System.out.println("Created Application: " + app.getDisplayName());

            // 5. Create a Function, set its execution image and limits.
            final String fnDisplayName = displayName + "-fn";
            final long memoryInMBs = 128L;
            final int timeoutInSeconds = 30;
            fn = createFunction(fnManagementClient, app.getId(), fnDisplayName, image, memoryInMBs, timeoutInSeconds);
            System.out.println("Created Function: " + fn.getDisplayName());

            // 6. Invoke the function!
            final String response = invokeFunction(fnInvokeClient, fn, payload);
            System.out.println("Response from function:  " + response);

        } finally {
            System.out.println("Cleaning up");

            if (fn != null) {
                deleteFunction(fnManagementClient, fn.getId());
                System.out.println("Deleted function: " + fn.getDisplayName());
            }

            if (app != null) {
                deleteApplication(fnManagementClient, app.getId());
                System.out.println("Deleted application: " + app.getDisplayName());
            }

            if (subnet != null) {
                deleteSubnet(vcnClient, subnet.getId());
                System.out.println("Deleted subnet: " + subnet.getDisplayName());
            }

            if (vcn != null) {
                deleteVcn(vcnClient, vcn);
                System.out.println("Deleted VCN: " + vcn.getDisplayName());
            }

            fnInvokeClient.close();     
            fnManagementClient.close();
            vcnClient.close();
            identityClient.close();
        }
    }

    /**
     * List the AvailabilityDomains.
     *
     * @param identityClient the service client to use to fetch the AvailabilityDomains 
     * @param compartmentId the OCID of the compartment to check
     *
     * @return the list of AvailabilityDomains
     *
     * @throws Exception if there is an error obtaining the AvailabilityDomains 
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

    /**
     * Creates a VCN and waits for it to become available to use.
     *
     * @param vcnClient the service client to use to create the VCN
     * @param compartmentId the OCID of the compartment where the VCN will be created
     * @param availabilityDomain the availability domain where the subnet will be created
     * @param cidrBlock the CidrBlock allocated for the VCN.
     *
     * @return the created VCN
     *
     * @throws Exception if there is an error waiting on the VCN to become available to use
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
     * Deletes a VCN and waits for it to be deleted.
     *
     * @param vcnClient the service client to use to delete the VCN
     * @param vcn the VCN to delete
     *
     * @throws Exception if there is an error waiting on the VCN to be deleted
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

    /**
     * Creates a subnet in a VCN and waits for the subnet to become available to use.
     *
     * @param vcnClient the service client to use to create the subnet
     * @param compartmentId the OCID of the compartment which owns the VCN
     * @param vcnId the ID of the VCN which will own the subnet
     * @param displayName the display name of the subnet 
     * @param availabilityDomain the availability domain where the subnet will be created
     * @param subnetCidrBlock the subnet CidrBlock allocated from the parent VCN range
     *
     * @return the created subnet
     *
     * @throws Exception if there is an error waiting on the subnet to become available to use
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
     * Deletes a subnet and waits for it to be deleted.
     *
     * @param vcnClient the service client to use to delete the subnet
     * @param subnetId the subnet to delete
     *
     * @throws Exception if there is an error waiting on the subnet to be deleted
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


    /**
     * Creates an Application and waits for the it to become available to use.
     *
     * @param fnManagementClient the service client to use to create the application
     * @param compartmentId the OCID of the compartment which owns the Application
     * @param displayName the availability domain where the subnet will be created
     * @param subnetIds a List of subnets (in different ADs) that will expose the function
     *
     * @return the created application
     *
     * @throws Exception if there is an error waiting on the application to become available to use
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
     * Deletes an Application and waits for it to be deleted.
     *
     * @param fnManagementClient the service client to use to delete the Application
     * @param applicationId the Application to delete
     *
     * @throws Exception if there is an error waiting on the Application to be deleted
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

    /**
     * Creates a Function and waits for the it to become available to use.
     *
     * @param fnManagementClient the service client to use to create the application
     * @param compartmentId the OCID of the compartment which owns the Application
     * @param displayName the availability domain where the subnet will be created
     * @param image an accessible OCIR image implementing the function to be executed
     * @param memoryInMBs the maximum ammount of memory available (128, 256, 512, 1024) to the function in MB
     * @param timeoutInSeconds the maximum ammout of time a function can execute (30 - 120) in seconds
     *
     * @return the created function
     *
     * @throws Exception if there is an error waiting on the function to become available to use
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
     * Deletes a Function and waits for it to be deleted.
     *
     * @param fnManagementClient the service client to use to delete the Function
     * @param functionId the Function to delete
     *
     * @throws Exception if there is an error waiting on the Function to be deleted
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
     * @param fnInvokedClient the service client to use to delete the Function
     * @param function the Function to invoke
     * @param payload the payload to pass to the function
     * 
     * @throws Exception if there is an error when invoking the function
     */
    private static String invokeFunction(
        final FunctionsInvokeClient fnInvokeClient,
        final Function fn,
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
            throw e;
        }

        return response;
    }

}

