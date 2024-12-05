Data Mesh Manager Agent for Snowflake
===

The agent for snowflake is a Spring Boot application that uses the [datamesh-manager-sdk](https://github.com/datamesh-manager/datamesh-manager-sdk) internally, and is available as a ready-to-use Docker image [datameshmanager/datamesh-manager-agent-snowflake](https://hub.docker.com/repository/docker/datameshmanager/datamesh-manager-agent-snowflake) to be deployed in your environment.

## Features

- **Asset Synchronization**: Sync tables and schemas from Snowflake to the Data Mesh Manager as Assets. 
- **Access Management**: Listen for AccessActivated and AccessDeactivated events in the Data Mesh Manager and grants access on Snowflake to the data consumer.

## Usage

Start the agent using Docker. You must pass the API keys as environment variables.

```
docker run \
  -v ./rsa_key.p8:/rsa_key.p8:ro
  -e DATAMESHMANAGER_CLIENT_APIKEY='insert-api-key-here' \
  -e DATAMESHMANAGER_CLIENT_SNOWFLAKE_ACCOUNT='<your-organization>-<your-account>' \
  -e DATAMESHMANAGER_CLIENT_SNOWFLAKE_USER='DATAMESHMANAGER_AGENT' \
  -e DATAMESHMANAGER_CLIENT_SNOWFLAKE_PRIVATEKEYFILE='file:/rsa_key.p8' \
  datameshmanager/datamesh-manager-agent-snowflake:latest
```

## Snowflake Setup

1. Create a new RSA key-pair ([Documentation](https://docs.snowflake.com/en/user-guide/key-pair-auth))
```
# Private Key (unencrypted)
openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -out rsa_key.p8 -nocrypt
```
```
# Public Key
openssl rsa -in rsa_key.p8 -pubout -out rsa_key.pub
```
And save the files at a secure location.


1. Create a new Snowflake role

```
create or replace role DATAMESHMANAGER;

-- do this for all databases, that should be synchronized to Data Mesh Manager
SET database = 'my_database';

grant usage on DATABASE IDENTIFIER($database) to role DATAMESHMANAGER;
grant usage on all schemas in database IDENTIFIER($database) to role DATAMESHMANAGER;
grant usage on future schemas in database IDENTIFIER($database) to role DATAMESHMANAGER;
grant references on all tables in database IDENTIFIER($database) to role DATAMESHMANAGER;
grant references on future tables in database IDENTIFIER($database) to role DATAMESHMANAGER;
grant references on all external tables in database IDENTIFIER($database) to role DATAMESHMANAGER;
grant references on future external tables in database IDENTIFIER($database) to role DATAMESHMANAGER;
grant references on all views in database IDENTIFIER($database) to role DATAMESHMANAGER;
grant references on future views in database IDENTIFIER($database) to role DATAMESHMANAGER;
grant select on all tables in database IDENTIFIER($database) to role DATAMESHMANAGER;
grant select on future tables in database IDENTIFIER($database) to role DATAMESHMANAGER;

grant create role on account to role DATAMESHMANAGER;
grant manage grants on account to role DATAMESHMANAGER;
```

2. Create a new Snowflake user (e.g. `DATAMESHMANAGER_AGENT`) with role DATAMESHMANAGER.

```
create user DATAMESHMANAGER_AGENT display_name = 'Data Mesh Manager User' password='' default_role = DATAMESHMANAGER;
grant role DATAMESHMANAGER to user DATAMESHMANAGER_AGENT;

```

3. Assign the public key to the Snowflake user. 

Omit the -----BEGIN PUBLIC KEY----- and -----END PUBLIC KEY----- lines and remove all line breaks.

```
cat rsa_key.pub | grep -v "BEGIN\|END" | tr -d '\n'
```
```
ALTER USER DATAMESHMANAGER_AGENT SET RSA_PUBLIC_KEY='MIIBIjANBgkqh...';
```

## Configuration

| Environment Variable                                        | Default Value                      | Description                                                                   |
|-------------------------------------------------------------|------------------------------------|-------------------------------------------------------------------------------|
| `DATAMESHMANAGER_CLIENT_HOST`                               | `https://api.datamesh-manager.com` | Base URL of the Data Mesh Manager API.                                        |
| `DATAMESHMANAGER_CLIENT_APIKEY`                             |                                    | API key for authenticating requests to the Data Mesh Manager.                 |
| `DATAMESHMANAGER_CLIENT_SNOWFLAKE_ACCOUT`                   |                                    | Snowflake account host URL in the form of `ORGANIZATION-ACCOUNT`.             |
| `DATAMESHMANAGER_CLIENT_SNOWFLAKE_USER`                     |                                    | The Snowflake user name as created abovem e.g. `DATAMESHMANAGER_AGENT`.       |
| `DATAMESHMANAGER_CLIENT_SNOWFLAKE_PRIVATEKEYFILE`           |                                    | The file path to the private key, as created above. In form `file:rsa_key.p8` |
| `DATAMESHMANAGER_CLIENT_SNOWFLAKE_ACCESSMANAGEMENT_AGENTID` | `snowflake-access-management`      | Identifier for the Snowflake access management agent.                         |
| `DATAMESHMANAGER_CLIENT_SNOWFLAKE_ACCESSMANAGEMENT_ENABLED` | `true`                             | Indicates whether Snowflake access management is enabled.                     |
| `DATAMESHMANAGER_CLIENT_SNOWFLAKE_ASSETS_AGENTID`           | `snowflake-assets`                 | Identifier for the Snowflake assets agent.                                    |
| `DATAMESHMANAGER_CLIENT_SNOWFLAKE_ASSETS_ENABLED`           | `true`                             | Indicates whether Snowflake asset tracking is enabled.                        |
| `DATAMESHMANAGER_CLIENT_SNOWFLAKE_ASSETS_POLLINTERVAL`      | `PT10M`                            | Polling interval for Snowflake asset updates, in ISO 8601 duration format.    |


## Access Management Flow

When an Access Request has been approved by the data product owner, and the start date is reached, Data Mesh Manager will publish an `AccessActivatedEvent`. When an end date is defined and reached, Data Mesh Manager will publish an `AccessDeactivatedEvent`. The agent listens for these events and grants access to the data consumer in Snowflake.

### Consumer Type: Data Product

Example:

- Provider is a data product with ID `p-200` and selected output port `p-200-op-210`. 
- The output port defines the schema `my_database.schema_220` in the server section.
- Consumer is a data product with ID `c-300`.
- Access ID is `a-100`.

Snowflake roles that will be created (if not exists) on `AccessActivatedEvent`:

The role names will be derived from the ID with a resource-type prefix. If a custom field `snowflakeRole` is defined on the resource in Data Mesh Manager, the value will be used as the role name instead of the ID.

- `access_a_100`
  - `grant USE SCHEMA my_database.schema_220`
  - `grant SELECT on all tables in schema "my_database.schema_220" to role access-a-100`
  - `grant SELECT on all future tables in schema "my_database.schema_220" to role access-a-100`
  - `grant SELECT on all views in schema "my_database.schema_220" to role access-a-100`
  - `grant SELECT on all views tables in schema "my_database.schema_220" to role access-a-100`
- `dataproduct_c_300`
  - `grant role access_a_100`
- `team_t_300`
  - `grant role access_a_100`
  - `grant role` to team members


Agent Actions on `AccessDeactivatedEvent`:

- Delete the role `access_a_100`


### Consumer Type: Team

Example:

- Provider is a data product with ID `p-200` and selected output port `p-200-op-210`.
- The output port defines the schema `my_catalog.schema_220` in the server section.
- Consumer is a team with ID `t-400`.
- Access ID is `a-101`.

Snowflake roles that will be created (if not exists) on `AccessActivatedEvent`:

The role names will be derived from the ID with a resource-type prefix. If a custom field `snowflakeRole` is defined on the resource in Data Mesh Manager, the value will be used as the role name instead of the ID.

Agent Actions on `AccessActivatedEvent`:

- `access_a_101`
  - `grant USE SCHEMA my_database.schema_220`
  - `grant SELECT on all tables in schema "my_database.schema_220" to role access_a_101`
  - `grant SELECT on all future tables in schema "my_database.schema_220" to role access_a_101`
- `team-t-400`
  - `grant role access_a_101`

Agent Actions on `AccessDeactivatedEvent`:

- Delete the group `access_a_101`


### Consumer Type: User

Example:

- Provider is a data product with ID `p-200` and selected output port `p-200-op-210`.
- The output port defines the schema `my_catalog.schema_220` in the server section.
- Consumer is an individual user with email address `alice@example.com` (Snowflake username alice).
- Access ID is `a-102`.

Agent Actions on `AccessActivatedEvent`:

- `access_a_102`
  - `grant USE SCHEMA my_database.schema_220`
  - `grant SELECT on all tables in schema "my_database.schema_220" to role access_a_102`
  - `grant SELECT on all future tables in schema "my_database.schema_220" to role access_a_102`
- `grant role access_a_102 to user alice`


Agent Actions on `AccessDeactivatedEvent`:

- Delete the group `access_a_102`



