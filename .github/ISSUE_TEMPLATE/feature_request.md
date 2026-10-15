name: 💡 Feature request
description: Suggest an idea (new data type, sync option, UI improvement)
title: "[Feature]: "
labels: ["enhancement"]
body:
  - type: textarea
    id: problem
    attributes:
      label: Problem / use case
      description: "What are you trying to achieve that the app doesn't do today?"
    validations:
      required: true
  - type: textarea
    id: solution
    attributes:
      label: Proposed solution
    validations:
      required: true
  - type: textarea
    id: context
    attributes:
      label: Health Connect / Garmin context
      description: "Record types, Garmin Connect behavior, links to docs — anything that helps scope it"
    validations:
      required: false
  - type: textarea
    id: alternatives
    attributes:
      label: Alternatives considered
    validations:
      required: false
