*** Settings ***
Resource       resources.robot
Suite Setup    Initialize Ranger Session (HTTP)

*** Variables ***

*** Test Cases ***
Get All Users
    ${response}=    GET On Session    ranger    /service/xusers/users
    Should Be Equal As Numbers    ${response.status_code}    200
    Log    ${response.json()}

