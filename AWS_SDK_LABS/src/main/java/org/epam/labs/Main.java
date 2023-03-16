package org.epam.labs;


import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Main {
    private static DynamoDbClient client = null;

    private static final String TABLE_1 = "CUSTOMER";
    private static final String TABLE_2 = "DEVICE";
    private static final String TABLE_3 = "CUSTOMER_DEVICE";

    public static void main(String[] args) {
        init();
        //fillTablesWithInitData();
        mergeTables();
        client.close();
    }

    private static void mergeTables() {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(TABLE_1)
                .build();

        List<Map<String, AttributeValue>> customers = client.scan(scanRequest).items().stream().map(HashMap::new).collect(Collectors.toList());

        List<GetItemRequest> requests = customers.stream().map(cust -> cust.get("DeviceId"))
                .map(id -> {
            Map<String, AttributeValue> map = new HashMap<>();
            map.put("device_id", id);
            return GetItemRequest.builder()
                    .key(map)
                    .tableName(TABLE_2)
                    .build();
        }).collect(Collectors.toList());

        List<Map<String, AttributeValue>> devices = null;

        try {
            devices = requests.stream().map(req -> client.getItem(req).item()).collect(Collectors.toList());
        } catch (DynamoDbException e) {
            System.out.println("error: " + e);
        }
        if(devices == null) {
            throw new RuntimeException("Devices is null");
        }

        for (Map<String, AttributeValue> cust : customers) {
            for (Map<String, AttributeValue> dev : devices) {
                if(dev.get("device_id").equals(cust.get("DeviceId"))) {
                    cust.replace("DeviceId", dev.get("Name"));
                }
            }
        }

        List<PutItemRequest> putRequests = customers.stream().map(map -> PutItemRequest.builder()
                .tableName(TABLE_3)
                .item(map)
                .build()).collect(Collectors.toList());

        try {
            putRequests.forEach(req -> client.putItem(req));
            System.out.println(TABLE_3 + " was successfully updated");

        } catch (ResourceNotFoundException e) {
            System.err.format("Error: The Amazon DynamoDB table \"%s\" can't be found.\n", TABLE_1);
            System.err.println("Be sure that it exists and that you've typed its name correctly!");
            System.exit(1);
        } catch (DynamoDbException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }

    }

    private static void fillTablesWithInitData() {

        Map<String, AttributeValue> mapCust = new HashMap<>();
        mapCust.put("customer_id", AttributeValue.builder().s("2").build());
        mapCust.put("FirstName", AttributeValue.builder().s("Maria").build());
        mapCust.put("LastName", AttributeValue.builder().s("Van Horen").build());
        mapCust.put("Year", AttributeValue.builder().n("1993").build());
        mapCust.put("DeviceId", AttributeValue.builder().s("55").build());

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_1)
                .item(mapCust)
                .build();
        try {
            client.putItem(request);
            System.out.println(TABLE_1 + " was successfully updated");

        } catch (ResourceNotFoundException e) {
            System.err.format("Error: The Amazon DynamoDB table \"%s\" can't be found.\n", TABLE_1);
            System.err.println("Be sure that it exists and that you've typed its name correctly!");
            System.exit(1);
        } catch (DynamoDbException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }

        Map<String, AttributeValue> mapDev = new HashMap<>();
        mapDev.put("device_id", AttributeValue.builder().s("55").build());
        mapDev.put("Name", AttributeValue.builder().s("SAMSUNG").build());
        PutItemRequest request1 = PutItemRequest.builder()
                .tableName(TABLE_2)
                .item(mapDev)
                .build();
        try {
            client.putItem(request1);
            System.out.println(TABLE_2 + " was successfully updated");

        } catch (ResourceNotFoundException e) {
            System.err.format("Error: The Amazon DynamoDB table \"%s\" can't be found.\n", TABLE_2);
            System.err.println("Be sure that it exists and that you've typed its name correctly!");
            System.exit(1);
        } catch (DynamoDbException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }


    }

    private static void init() {
        ProfileCredentialsProvider credentialsProvider = ProfileCredentialsProvider.create();
        Region region = Region.EU_CENTRAL_1;
        client = DynamoDbClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .build();
        createTablesIfNotExists();

    }

    private static List<String> getAllExistingTables() {
        ListTablesRequest request = ListTablesRequest.builder().build();
        ListTablesResponse response = client.listTables(request);
        return response.tableNames();
    }

    private static boolean checkIfTableExists(String tableName, List<String> listOfNames) {
        return !listOfNames.contains(tableName);
    }

    private static void createTablesIfNotExists() {
        List<String> allTables = getAllExistingTables();
        if (checkIfTableExists(TABLE_1, allTables)) {
            createTable(TABLE_1, "customer_id");
        }
        if (checkIfTableExists(TABLE_2, allTables)) {
            createTable(TABLE_2, "device_id");
        }
        if (checkIfTableExists(TABLE_3, allTables)) {
            createTable(TABLE_3, "customer_id");
        }
    }

    private static void createTable(String tableName, String key) {
        DynamoDbWaiter dbWaiter = client.waiter();
        CreateTableRequest request = CreateTableRequest.builder()
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName(key)
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(KeySchemaElement.builder()
                        .attributeName(key)
                        .keyType(KeyType.HASH)
                        .build())
                .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(5L)
                        .writeCapacityUnits(5L)
                        .build())
                .tableName(tableName)
                .build();

        try {
            CreateTableResponse response = client.createTable(request);
            DescribeTableRequest tableRequest = DescribeTableRequest.builder()
                    .tableName(tableName)
                    .build();
            System.out.println("response " + response);
            // Wait until the Amazon DynamoDB table is created.
            WaiterResponse<DescribeTableResponse> waiterResponse = dbWaiter.waitUntilTableExists(tableRequest);
            waiterResponse.matched().response().ifPresent(System.out::println);

        } catch (DynamoDbException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}