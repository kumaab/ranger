*** Settings ***
Library    RequestsLibrary

*** Variables ***
${RANGER_HOST}    localhost
${PORT}           6080
@{CREDS}          admin    rangerR0cks!

*** Keywords ***
Initialize Ranger Session (HTTP)
	Create Session    ranger    http://${RANGER_HOST}:${PORT}    auth=${CREDS}

