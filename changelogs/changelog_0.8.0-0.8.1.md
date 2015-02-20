## Changes from 0.8.0 to 0.8.1

#### New endpoint `/v2/deployments/generate`

When posting a group definition to this endpoint, it will
return the deployment steps Marathon would execute to deploy
this group.

#### POST `/v2/apps` rejects existing ids

If an app with an already existing id is posted to this endpoint,
it will now be rejected

#### PUT `/v2/apps/{id}` always returns deployment info

In versions <= 0.8.0 it used to return the complete app definition
if the resource didn't exist before. To be consistent in the response,
it has been changed to always return the deployment info instead. However
it still return a `201 - Created` if the resource didn't exist.

#### GET `/v2/queue` includes delay

In 0.8.0 the queueing behavior has changed and the output of this endpoint
did not contain the delay field anymore. In 0.8.1 we re-added this field.