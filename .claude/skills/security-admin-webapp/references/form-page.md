<!--
 - Licensed to the Apache Software Foundation (ASF) under one or more
 - contributor license agreements.  See the NOTICE file distributed with
 - this work for additional information regarding copyright ownership.
 - The ASF licenses this file to You under the Apache License, Version 2.0
 - (the "License"); you may not use this file except in compliance with
 - the License.  You may obtain a copy of the License at
 -
 -   http://www.apache.org/licenses/LICENSE-2.0
 -
 - Unless required by applicable law or agreed to in writing, software
 - distributed under the License is distributed on an "AS IS" BASIS,
 - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 - See the License for the specific language governing permissions and
 - limitations under the License.
 -->

# Form page recipe

Canonical file: `src/views/UserGroupRoleListing/users_details/UserForm.jsx`.
Larger variants: `views/ServiceManager/ServiceForm.jsx` (class + `withRouter`, `final-form-arrays` `FieldArray`),
`views/SecurityZone/SecurityZoneForm.jsx`, `views/PolicyListing/AddUpdatePolicyForm.jsx`.

## Skeleton

```jsx
<Form
  onSubmit={handleSubmit}
  keepDirtyOnReinitialize={true}
  validate={validateForm}
  initialValues={setUserFormData()}
  mutators={{ ...arrayMutators }}                       /* only if FieldArray is used */
  render={({ handleSubmit, form, submitting, values, invalid, errors, dirty }) => (
    <div className="wrap user-role-grp-form">
      <PromptDialog isDirtyField={dirty} isUnblock={preventUnBlock} />
      <form onSubmit={(event) => { handleSubmit(event); }}>
        ...fields...
        <div className="row form-actions">
          <div className="col-md-9 offset-md-3">
            <Button variant="primary" size="sm" data-id="save" data-cy="save" disabled={submitting}
              onClick={() => {
                if (invalid) {
                  let selector = document.getElementById("isError")
                    || document.getElementById(Object.keys(errors)[0])
                    || document.querySelector(`input[name=${Object.keys(errors)[0]}]`)
                    || document.querySelector(`input[id=${Object.keys(errors)[0]}]`)
                    || document.querySelector(`span[className="invalid-field"]`);
                  scrollToError(selector);
                }
                handleSubmit(values);
              }}>Save</Button>
            <Button variant="secondary" size="sm" data-id="cancel" data-cy="cancel" onClick={closeForm}>Cancel</Button>
          </div>
        </div>
      </form>
    </div>
  )}
/>
```

## Field markup

Render-prop `<Field>`, Bootstrap grid, error class toggling, `InfoIcon` from `Utils/XAUtils`:

```jsx
<Field name="name">
  {({ input, meta }) => (
    <Row className="form-group">
      <Col xs={3}><label className="form-label float-end">User Name *</label></Col>
      <Col xs={4} className="position-relative">
        <input {...input} type="text" name="name"
          id={meta.error && meta.touched ? "isError" : "name"}
          className={meta.error && meta.touched ? "form-control border-danger" : "form-control"}
          data-cy="name" onBlur={(e) => trimInputValue(e, input)} />
        <InfoIcon css="input-box-info-icon" position="right" message={RegexMessage.MESSAGE.userNameValidationMsg} />
        {meta.error && meta.touched && (<span className="invalid-field">{meta.error}</span>)}
      </Col>
    </Row>
  )}
</Field>
```

The `id="isError"` trick is what `scrollToError` relies on; keep it.

## Selects

`react-select` (`Select`, `react-select/async` `AsyncSelect`, `react-select/async-creatable`) inside a `Field` render function.
Shared styles/behaviour from `Components/CommonComponents`: `selectInputCustomStyles`, `selectInputCustomErrorStyles`,
`selectInputWrappingCustomStyles`, `ConfirmationClearIndicator`; error styles via `getSelectBoxErrorStyles(meta)` from `Utils/XAUtils`.

```jsx
<AsyncSelect {...input} id="groupIdList" data-cy="groupIdList" cacheOptions defaultOptions isMulti
  loadOptions={loadGroups} styles={selectInputCustomStyles} tabSelectsValue={false}
  placeholder="Select Groups" clearConfirmMessage="Groups"
  components={{ ClearIndicator: ConfirmationClearIndicator }} />
```

`loadOptions` is `async (input) => (await fetchApi({ url: "xusers/lookup/groups", params: { name: input } })).data.vXStrings.map(g => ({ label: g.value, value: g.value }))`.

## Validation

One record-level `validate(values)` returning an `errors` object. Regexes and messages come from `RegexValidation` (`Utils/XAEnums`)
and `RegexMessage` (`Utils/XAMessages`); add new ones there, not inline.

```js
const validateForm = (values) => {
  const errors = {};
  if (!values.name) {
    errors.name = "Required";
  } else if (!RegexValidation.NAME_VALIDATION.regexforNameValidation.test(values.name)) {
    errors.name = RegexValidation.NAME_VALIDATION.nameValidationMessage;
  }
  return errors;
};
```

## Submit

```js
const handleSubmit = async (formData) => {
  const data = { ...map form values to the REST payload... };
  setPreventUnblock(true);
  setBlockUI(true);
  try {
    await fetchApi({ url: isEdit ? `xusers/secure/users/${id}` : "xusers/secure/users", method: isEdit ? "put" : "post", data });
    setBlockUI(false);
    toast.success(`User ${isEdit ? "updated" : "created"} successfully!!`);
    navigate("/users/usertab", { state: { showLastPage: true, addPageData: tablePageData } });
  } catch (error) {
    setBlockUI(false);
    serverError(error);
    console.error(`Error occurred while saving user ${error}`);
  }
};
```

## Unsaved-changes guard

Each form declares a local `PromptDialog`:

```jsx
const PromptDialog = (props) => {
  const { isDirtyField, isUnblock } = props;
  usePrompt("Are you sure you want to leave", isDirtyField && !isUnblock);
  return null;
};
```

`usePrompt` (`Hooks/usePrompt`) uses `UNSAFE_NavigationContext` + `window.confirm`. Set the `preventUnBlock` flag before a successful submit or cancel.

## Inline editing

`components/Editable.jsx` renders a popover editor (`type` in `select | checkbox | input | radio | custom`). Used for policy permission rows,
GDS views, and `ServiceAuditFilter`. Prefer it over ad-hoc popovers.
