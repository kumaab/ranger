*** Settings ***
Resource    resources.robot

*** Test Cases ***
Create Ranger Session
    [Documentation]    Establish a session with Ranger Admin
    Initialize Ranger Session (HTTP)

