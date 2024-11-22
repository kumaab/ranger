*** Settings ***
Library        Collections
Resource       resources.robot
Suite Setup    Initialize Ranger Session (HTTP)

*** Variables ***
${POLICY_NAME}      hbase-archive
${HDFS_SVC_NAME}    dev_hdfs
${HIVE_SVC_NAME}    dev_hive
${HIVE_POLICY}      test policy
${QPARAMS}          servicename=dev_hive&policyname=test policy
@{TEST_DB}          test_db
@{TEST_TABLE}       test_tbl
@{TEST_COL}         *
&{ACCESS_CR}        type=create    isAllowed=${true}
&{ACCESS_AL}        type=alter     isAllowed=${true}
&{ACCESS_DR}        type=drop      isAllowed=${true}
@{ACCESSES}         ${ACCESS_CR}    ${ACCESS_AL}
@{D_ACCESSES}       ${ACCESS_DR}
@{USERS}            admin

*** Test Cases ***
Get All Policies
    ${response}=    GET On Session    ranger    /service/public/v2/api/policy
    Should Be Equal As Numbers    ${response.status_code}    200
    Log    ${response.json()}


Create New Hive Policy
    ${database}             Create Dictionary    values=${TEST_DB}       isExcludes=${false}    isRecursive=${false}
    ${table}                Create Dictionary    values=${TEST_TABLE}    isExcludes=${false}    isRecursive=${false}
    ${column}               Create Dictionary    values=${TEST_COL}      isExcludes=${false}    isRecursive=${false}

    ${resources}            Create Dictionary    database=${database}      table=${table}    column=${column}

    ${policy_item}          Create Dictionary    delegateAdmin=${false}    users=${USERS}    accesses=${ACCESSES}
    ${deny_policy_item}     Create Dictionary    delegateAdmin=${false}    users=${USERS}    accesses=${D_ACCESSES}

    ${policy_items}         Create List    ${policy_item}
    ${deny_policy_items}    Create List    ${deny_policy_item}
    ${payload}              Create Dictionary    service=${HIVE_SVC_NAME}    isEnabled=${true}    isDenyAllElse=${false}    name=test policy    resources=${resources}    policyItems=${policy_items}    denyPolicyItems=${deny_policy_items}

    # Log and Verify Payload
    Log    ${payload}

    ${response}=    POST On Session    ranger    /service/public/v2/api/policy    json=${payload}
    Should Be Equal As Numbers    ${response.status_code}    200
    Log    ${response.json()}


Delete Hive Policy
    ${response}=    DELETE On Session    ranger    /service/public/v2/api/policy    params=${QPARAMS}
    Should Be Equal As Numbers    ${response.status_code}    204

