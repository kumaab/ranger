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

# Toolchain and dev workflow

## package.json

All dependencies are under `devDependencies` (there is no `dependencies` block). Scripts:

```json
"start": "webpack serve --config ./config/webpack.dev.config.js",
"build": "webpack --config ./config/webpack.prod.config.js",
"test":  "echo \"Error: no test specified\" && exit 1",
"update-babel-config": "node update-babel-config.js"
```

Key libs: react/react-dom 18, react-router-dom 6, react-bootstrap 2 + bootstrap 5, react-final-form 6 (+ final-form-arrays), react-table 7,
react-select 5, axios, moment-timezone, dateformat, lodash, qs, react-toastify 8, react-idle-timer 5, react-paginate, react-infinite-scroll-component,
react-datetime, font-awesome 4.7, chart.js 4 + react-chartjs-2 5, esprima (expression validation in `Editable.jsx`).

## webpack (`config/`)

- `paths.js`: `mainEntryPath` = `src/index.jsx`, output `dist`, dev host `0.0.0.0`, port 8888 (`UI_HOST`/`UI_PORT` env override), proxy target `http://localhost:6080`.
- `webpack.config.js`: aliases `Views`, `Images`, `Utils`, `Components`, `Hooks`; output `dist/[name].[contenthash].js`; `HtmlWebpackPlugin` from `src/index.html`.
- `webpack.dev.config.js`: `historyApiFallback`, HMR, proxies `/service`, `/login`, `/logout` to the admin server.
- `webpack.prod.config.js`: `MiniCssExtractPlugin` -> `styles/[name].[contenthash].css`.

## babel

`babel.config.json`: `@babel/preset-env` (`targets: { esmodules: true }`) + `@babel/preset-react`. `update-babel-config.js` adds
`babel-plugin-istanbul` when Maven runs with `-DskipJSCoverage=false` (default `true`).

## Maven (`security-admin/pom.xml`)

`maven-resources-plugin` copies `react-webapp/` to `target/react-webapp`, then `frontend-maven-plugin` 1.12.1 runs in `generate-resources`:
`install-node-and-npm` (Node `v20.19.5`, npm `10.8.2`, into `target/react-build`), `npm ci`, `npm run build`. `dist/` is copied into the war;
`maven-war-plugin` excludes `react-webapp/**` from the war. Build only the UI-bearing module with:

```bash
mvn -pl security-admin -am clean package -DskipTests
```

## Local dev loop

1. Run Ranger Admin on `localhost:6080` (for example via `dev-support/ranger-docker`).
2. `cd security-admin/src/main/webapp/react-webapp && npm ci && npm start`, open `http://localhost:8888`.
3. Format before sending a PR: `npx prettier --write src/` with the repo `.prettierrc`; do not commit the resulting `package*.json` diff.

## Formatting

`.prettierrc`: `printWidth 80`, `tabWidth 2`, `semi true`, `singleQuote false`, `trailingComma "none"`, `arrowParens "always"`, `endOfLine "lf"`.
`.prettierignore`: `dist/`, `node_modules/`, `src/index.html`, `src/images/`, `README.md`, `config/`. No ESLint, no CI enforcement.
