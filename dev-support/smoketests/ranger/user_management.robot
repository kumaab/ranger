*** Settings ***
Library        user_management.TestUserManagement    http://localhost:6080    admin    rangerR0cks!
Library        Collections
Library        JSONLibrary

*** Variables ***

*** Test Cases ***
Get All Users
    Find Users

Get All Groups
    Find Groups

Create Test User
    ${response}             Create User             test_user_1
    ${id}                   Get Value From Json     ${response}         $.id
    Set Suite Variable      ${USER_ID}              ${id}
    Log                     ${response}

Create Test Group
    ${response}             Create Group            test_group_1
    ${id}                   Get Value From Json     ${response}         $.id
    Set Suite Variable      ${GROUP_ID}             ${id}
    Log                     ${response}

Add Test User To Test Group
    ${response}                     Add To Group            test_group_1        ${GROUP_ID}[0]     ${USER_ID}[0]
    ${users}                        List Users In Group     test_group_1
    List Should Contain Value       ${users}                test_user_1


List Users In Hadoop Group
    [Documentation]     Check existence of users: hdfs, yarn
    ${users}                        List Users In Group     hadoop
    List Should Contain Value       ${users}                hdfs
    List Should Contain Value       ${users}                yarn

List Groups For Ranger
    ${groups}           List Groups For User        ranger
    List Should Contain Value       ${groups}       ranger


List GroupUsers
    ${response}     List Group Users
    Log     ${response}

Delete Last User Created
    Delete User By Id           ${USER_ID}[0]

Delete Last Group Created
    Delete Group By Id          ${GROUP_ID}[0]

