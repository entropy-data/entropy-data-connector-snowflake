Entropy Data Connector for Snowflake
===

The connector for Snowflake is a Spring Boot application that uses the [entropy-data-sdk](https://github.com/entropy-data/entropy-data-sdk) internally, and is available as a ready-to-use Docker image [entropydata/entropy-data-connector-snowflake](https://hub.docker.com/r/entropydata/entropy-data-connector-snowflake) to be deployed in your environment.

## Features

- **Asset Synchronization**: Sync tables and schemas from Snowflake to Entropy Data as Assets. 
- **Access Management**: Listen for AccessActivated and AccessDeactivated events in Entropy Data and grants access on Snowflake to the data consumer.
  The Snowflake schema (database and schema) is resolved from the data contract linked by the provider output port, which must be in ODCS (Open Data Contract Standard) format; the legacy Data Contract Specification (DCS) is not supported.

## Usage

Start the connector using Docker. You must pass the API keys as environment variables.

```
docker run \
  -v ./rsa_key.p8:/rsa_key.p8:ro
  -e ENTROPYDATA_CLIENT_APIKEY='insert-api-key-here' \
  -e ENTROPYDATA_CLIENT_SNOWFLAKE_ACCOUNT='<your-organization>-<your-account>' \
  -e ENTROPYDATA_CLIENT_SNOWFLAKE_USER='ENTROPYDATA_CONNECTOR' \
  -e ENTROPYDATA_CLIENT_SNOWFLAKE_PRIVATEKEYFILE='file:/rsa_key.p8' \
  entropydata/entropy-data-connector-snowflake:latest
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
create or replace role ENTROPYDATA;

-- do this for all databases, that should be synchronized to Entropy Data
SET database = 'my_database';

grant usage on DATABASE IDENTIFIER($database) to role ENTROPYDATA;
grant usage on all schemas in database IDENTIFIER($database) to role ENTROPYDATA;
grant usage on future schemas in database IDENTIFIER($database) to role ENTROPYDATA;
grant references on all tables in database IDENTIFIER($database) to role ENTROPYDATA;
grant references on future tables in database IDENTIFIER($database) to role ENTROPYDATA;
grant references on all external tables in database IDENTIFIER($database) to role ENTROPYDATA;
grant references on future external tables in database IDENTIFIER($database) to role ENTROPYDATA;
grant references on all views in database IDENTIFIER($database) to role ENTROPYDATA;
grant references on future views in database IDENTIFIER($database) to role ENTROPYDATA;
grant select on all tables in database IDENTIFIER($database) to role ENTROPYDATA;
grant select on future tables in database IDENTIFIER($database) to role ENTROPYDATA;

grant create role on account to role ENTROPYDATA;
grant manage grants on account to role ENTROPYDATA;
```

2. Create a new Snowflake user (e.g. `ENTROPYDATA_CONNECTOR`) with role ENTROPYDATA.

```
create user ENTROPYDATA_CONNECTOR display_name = 'Entropy Data User' password='' default_role = ENTROPYDATA;
grant role ENTROPYDATA to user ENTROPYDATA_CONNECTOR;

```

3. Assign the public key to the Snowflake user. 

Omit the -----BEGIN PUBLIC KEY----- and -----END PUBLIC KEY----- lines and remove all line breaks.

```
cat rsa_key.pub | grep -v "BEGIN\|END" | tr -d '\n'
```
```
ALTER USER ENTROPYDATA_CONNECTOR SET RSA_PUBLIC_KEY='MIIBIjANBgkqh...';
```

## Configuration

| Environment Variable                                        | Default Value                      | Description                                                                   |
|-------------------------------------------------------------|------------------------------------|-------------------------------------------------------------------------------|
| `ENTROPYDATA_CLIENT_HOST`                               | `https://api.entropy-data.com` | Base URL of the Entropy Data API.                                        |
| `ENTROPYDATA_CLIENT_APIKEY`                             |                                    | API key for authenticating requests to Entropy Data.                 |
| `ENTROPYDATA_CLIENT_SNOWFLAKE_ACCOUNT`                   |                                    | Snowflake account host URL in the form of `ORGANIZATION-ACCOUNT`.             |
| `ENTROPYDATA_CLIENT_SNOWFLAKE_USER`                     |                                    | The Snowflake user name as created above, e.g. `ENTROPYDATA_CONNECTOR`.       |
| `ENTROPYDATA_CLIENT_SNOWFLAKE_PRIVATEKEYFILE`           |                                    | The file path to the private key, as created above. In form `file:rsa_key.p8` |
| `ENTROPYDATA_CLIENT_SNOWFLAKE_ACCESSMANAGEMENT_CONNECTORID` | `snowflake-access-management`      | Identifier for the Snowflake access management connector.                         |
| `ENTROPYDATA_CLIENT_SNOWFLAKE_ACCESSMANAGEMENT_ENABLED` | `true`                             | Indicates whether Snowflake access management is enabled.                     |
| `ENTROPYDATA_CLIENT_SNOWFLAKE_ASSETS_CONNECTORID`           | `snowflake-assets`                 | Identifier for the Snowflake assets connector.                                    |
| `ENTROPYDATA_CLIENT_SNOWFLAKE_ASSETS_ENABLED`           | `true`                             | Indicates whether Snowflake asset tracking is enabled.                        |
| `ENTROPYDATA_CLIENT_SNOWFLAKE_ASSETS_POLLINTERVAL`      | `PT10M`                            | Polling interval for Snowflake asset updates, in ISO 8601 duration format.    |


## Access Management Flow

When an Access Request has been approved by the data product owner, and the start date is reached, Entropy Data will publish an `AccessActivatedEvent`. When an end date is defined and reached, Entropy Data will publish an `AccessDeactivatedEvent`. The connector listens for these events and grants access to the data consumer in Snowflake.

The Snowflake schema (database and schema) is resolved from the ODCS data contract linked by the provider output port, with the output port's own server configuration as fallback.

### Consumer Type: Data Product

Example:

- Provider is a data product with ID `p-200` and selected output port `p-200-op-210`. 
- The linked data contract defines the Snowflake server with database `my_database` and schema `schema_220`.
- Consumer is a data product with ID `c-300`.
- Access ID is `a-100`.

Snowflake roles that will be created (if not exists) on `AccessActivatedEvent`:

The role names will be derived from the ID with a resource-type prefix. If a custom field `snowflakeRole` is defined on the resource in Entropy Data, the value will be used as the role name instead of the ID.

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


Connector Actions on `AccessDeactivatedEvent`:

- Delete the role `access_a_100`


### Consumer Type: Team

Example:

- Provider is a data product with ID `p-200` and selected output port `p-200-op-210`.
- The linked data contract defines the Snowflake server with database `my_database` and schema `schema_220`.
- Consumer is a team with ID `t-400`.
- Access ID is `a-101`.

Snowflake roles that will be created (if not exists) on `AccessActivatedEvent`:

The role names will be derived from the ID with a resource-type prefix. If a custom field `snowflakeRole` is defined on the resource in Entropy Data, the value will be used as the role name instead of the ID.

Connector Actions on `AccessActivatedEvent`:

- `access_a_101`
  - `grant USE SCHEMA my_database.schema_220`
  - `grant SELECT on all tables in schema "my_database.schema_220" to role access_a_101`
  - `grant SELECT on all future tables in schema "my_database.schema_220" to role access_a_101`
- `team-t-400`
  - `grant role access_a_101`

Connector Actions on `AccessDeactivatedEvent`:

- Delete the role `access_a_101`


### Consumer Type: User

Example:

- Provider is a data product with ID `p-200` and selected output port `p-200-op-210`.
- The linked data contract defines the Snowflake server with database `my_database` and schema `schema_220`.
- Consumer is an individual user with email address `alice@example.com` (Snowflake username alice).
- Access ID is `a-102`.

Connector Actions on `AccessActivatedEvent`:

- `access_a_102`
  - `grant USE SCHEMA my_database.schema_220`
  - `grant SELECT on all tables in schema "my_database.schema_220" to role access_a_102`
  - `grant SELECT on all future tables in schema "my_database.schema_220" to role access_a_102`
- `grant role access_a_102 to user alice`


Connector Actions on `AccessDeactivatedEvent`:

- Delete the role `access_a_102`
