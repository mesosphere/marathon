package mesosphere.marathon.integration.facades

import org.scalatest.{ Matchers, GivenWhenThen, FunSuite }
import play.api.libs.json.Json

class MesosFormatsTest extends FunSuite with Matchers with GivenWhenThen {
  import MesosFacade._
  import MesosFormats._

  test("parse sample") {
    Given("a sample state.json")
    val f = new Fixture

    When("parsing it")
    val status = Json.parse(f.sampleStatusJson).as[ITMesosState]

    Then("we can extract some base info")
    status.version should equal("0.28.0")

    And("we have info about one agent")
    status.agents should have size (1)
    val agent = status.agents.head

    And("resources of that agent are correct")

    /*
      * "resources": {
          "cpus": 8.0,
          "disk": 52830.0,
          "mem": 5078.0,
          "ports": "[31000-32000]"
        },
      */
    agent.resources should equal(
      ITResources(
        "cpus" -> 8.0,
        "disk" -> 52830.0,
        "mem" -> 5078.0,
        "ports" -> "[31000-32000]"
      )
    )
    /* "used_resources": {
        "cpus": 1.0,
        "disk": 128.0,
        "mem": 128.0,
        "ports": "[31903-31903]"
      }, */
    agent.usedResources should equal(
      ITResources(
        "cpus" -> 1.0,
        "disk" -> 128.0,
        "mem" -> 128.0,
        "ports" -> "[31903-31903]"
      )
    )
    /*
      "offered_resources": {
        "cpus": 0.0,
        "disk": 0.0,
        "mem": 0.0
      },
     */
    agent.offeredResources should equal(
      ITResources(
        "cpus" -> 0.0,
        "disk" -> 0.0,
        "mem" -> 0.0
      )
    )
    /*
      "reserved_resources": {
        "marathon": {
          "cpus": 1.1,
          "disk": 138.0,
          "mem": 144.0,
          "ports": "[31903-31903]"
        }
      },
     */
    agent.reservedResourcesByRole should equal(
      Map(
        "marathon" -> ITResources(
          "cpus" -> 1.1,
          "disk" -> 138.0,
          "mem" -> 144.0,
          "ports" -> "[31903-31903]"
        )
      )
    )
    /*
      "unreserved_resources": {
        "cpus": 6.9,
        "disk": 52692.0,
        "mem": 4934.0,
        "ports": "[31000-31902, 31904-32000]"
      },
    */
    agent.unreservedResources should equal(
      ITResources(
        "cpus" -> 6.9,
        "disk" -> 52692.0,
        "mem" -> 4934.0,
        "ports" -> "[31000-31902, 31904-32000]"
      )
    )
  }

  class Fixture {
    val sampleStatusJson =
      """
        |{
        |  "version": "0.28.0",
        |  "git_sha": "ab1ec6a0d9ed4ba7180f4576c1bb267e58f94e00",
        |  "git_tag": "0.28.0-rc1",
        |  "build_date": "2016-03-04 05:39:57",
        |  "build_time": 1457069997.0,
        |  "build_user": "root",
        |  "start_time": 1457617308.28327,
        |  "elected_time": 1457617308.29501,
        |  "id": "3b81e796-9d06-4556-99d4-a5ac09a229d3",
        |  "pid": "master@192.168.99.10:5050",
        |  "hostname": "master",
        |  "activated_slaves": 1.0,
        |  "deactivated_slaves": 0.0,
        |  "leader": "master@192.168.99.10:5050",
        |  "log_dir": "/var/log/mesos",
        |  "flags": {
        |    "acls": "register_frameworks { principals {   type: ANY } roles {   type: ANY }} run_tasks { principals {   type: ANY } users {   type: ANY }}",
        |    "allocation_interval": "1secs",
        |    "allocator": "HierarchicalDRF",
        |    "authenticate": "false",
        |    "authenticate_http": "false",
        |    "authenticate_slaves": "false",
        |    "authenticators": "crammd5",
        |    "authorizers": "local",
        |    "credentials": "/etc/mesos.cfg/credentials",
        |    "framework_sorter": "drf",
        |    "help": "false",
        |    "hostname_lookup": "true",
        |    "http_authenticators": "basic",
        |    "initialize_driver_logging": "true",
        |    "ip": "192.168.99.10",
        |    "log_auto_initialize": "true",
        |    "log_dir": "/var/log/mesos",
        |    "logbufsecs": "0",
        |    "logging_level": "INFO",
        |    "max_completed_frameworks": "50",
        |    "max_completed_tasks_per_framework": "1000",
        |    "max_slave_ping_timeouts": "5",
        |    "port": "5050",
        |    "quiet": "false",
        |    "quorum": "1",
        |    "recovery_slave_removal_limit": "100%",
        |    "registry": "replicated_log",
        |    "registry_fetch_timeout": "1mins",
        |    "registry_store_timeout": "20secs",
        |    "registry_strict": "false",
        |    "root_submissions": "true",
        |    "slave_ping_timeout": "15secs",
        |    "slave_reregister_timeout": "10mins",
        |    "user_sorter": "drf",
        |    "version": "false",
        |    "webui_dir": "/usr/share/mesos/webui",
        |    "work_dir": "/var/lib/mesos",
        |    "zk": "zk://localhost:2181/mesos",
        |    "zk_session_timeout": "10secs"
        |  },
        |  "slaves": [
        |    {
        |      "id": "32edd5ac-248e-437d-bc31-60ef6ece59ec-S0",
        |      "pid": "slave(1)@192.168.99.10:5051",
        |      "hostname": "master",
        |      "registered_time": 1457617309.27261,
        |      "reregistered_time": 1457617309.27294,
        |      "resources": {
        |        "cpus": 8.0,
        |        "disk": 52830.0,
        |        "mem": 5078.0,
        |        "ports": "[31000-32000]"
        |      },
        |      "used_resources": {
        |        "cpus": 1.0,
        |        "disk": 128.0,
        |        "mem": 128.0,
        |        "ports": "[31903-31903]"
        |      },
        |      "offered_resources": {
        |        "cpus": 0.0,
        |        "disk": 0.0,
        |        "mem": 0.0
        |      },
        |      "reserved_resources": {
        |        "marathon": {
        |          "cpus": 1.1,
        |          "disk": 138.0,
        |          "mem": 144.0,
        |          "ports": "[31903-31903]"
        |        }
        |      },
        |      "unreserved_resources": {
        |        "cpus": 6.9,
        |        "disk": 52692.0,
        |        "mem": 4934.0,
        |        "ports": "[31000-31902, 31904-32000]"
        |      },
        |      "attributes": {
        |
        |      },
        |      "active": true,
        |      "version": "0.28.0"
        |    }
        |  ],
        |  "frameworks": [
        |
        |  ],
        |  "completed_frameworks": [
        |
        |  ],
        |  "orphan_tasks": [
        |    {
        |      "id": "test.753fc1ec-e5d7-11e5-a741-ac87a3211095",
        |      "name": "test",
        |      "framework_id": "70c87ff5-537d-4576-ba03-62e8130787ac-0001",
        |      "executor_id": "",
        |      "slave_id": "32edd5ac-248e-437d-bc31-60ef6ece59ec-S0",
        |      "state": "TASK_FINISHED",
        |      "resources": {
        |        "cpus": 1.0,
        |        "disk": 0.0,
        |        "mem": 128.0,
        |        "ports": "[31308-31308]"
        |      },
        |      "statuses": [
        |        {
        |          "state": "TASK_RUNNING",
        |          "timestamp": 1457514925.78141,
        |          "container_status": {
        |            "network_infos": [
        |              {
        |                "ip_address": "192.168.99.10",
        |                "ip_addresses": [
        |                  {
        |                    "ip_address": "192.168.99.10"
        |                  }
        |                ]
        |              }
        |            ]
        |          }
        |        },
        |        {
        |          "state": "TASK_FINISHED",
        |          "timestamp": 1457515925.81577,
        |          "container_status": {
        |            "network_infos": [
        |              {
        |                "ip_address": "192.168.99.10",
        |                "ip_addresses": [
        |                  {
        |                    "ip_address": "192.168.99.10"
        |                  }
        |                ]
        |              }
        |            ]
        |          }
        |        }
        |      ],
        |      "discovery": {
        |        "visibility": "FRAMEWORK",
        |        "name": "test",
        |        "ports": {
        |          "ports": [
        |            {
        |              "number": 10000,
        |              "protocol": "tcp"
        |            }
        |          ]
        |        }
        |      }
        |    },
        |    {
        |      "id": "test.b759a32a-e608-11e5-979a-da5f91e88a84",
        |      "name": "test",
        |      "framework_id": "ee85eedd-674c-40f9-ae8f-67158a923d65-0000",
        |      "executor_id": "",
        |      "slave_id": "32edd5ac-248e-437d-bc31-60ef6ece59ec-S0",
        |      "state": "TASK_RUNNING",
        |      "resources": {
        |        "cpus": 1.0,
        |        "disk": 128.0,
        |        "mem": 128.0,
        |        "ports": "[31903-31903]"
        |      },
        |      "statuses": [
        |        {
        |          "state": "TASK_RUNNING",
        |          "timestamp": 1457536474.32426,
        |          "container_status": {
        |            "network_infos": [
        |              {
        |                "ip_address": "192.168.99.10",
        |                "ip_addresses": [
        |                  {
        |                    "ip_address": "192.168.99.10"
        |                  }
        |                ]
        |              }
        |            ]
        |          }
        |        }
        |      ],
        |      "discovery": {
        |        "visibility": "FRAMEWORK",
        |        "name": "test",
        |        "ports": {
        |          "ports": [
        |            {
        |              "number": 10000,
        |              "protocol": "tcp"
        |            }
        |          ]
        |        }
        |      },
        |      "container": {
        |        "type": "MESOS",
        |        "mesos": {
        |
        |        }
        |      }
        |    },
        |    {
        |      "id": "resident1.02ccdd3e-e60a-11e5-979a-da5f91e88a84",
        |      "name": "resident1",
        |      "framework_id": "ee85eedd-674c-40f9-ae8f-67158a923d65-0000",
        |      "executor_id": "",
        |      "slave_id": "32edd5ac-248e-437d-bc31-60ef6ece59ec-S0",
        |      "state": "TASK_FINISHED",
        |      "resources": {
        |        "cpus": 0.1,
        |        "disk": 10.0,
        |        "mem": 16.0
        |      },
        |      "statuses": [
        |        {
        |          "state": "TASK_RUNNING",
        |          "timestamp": 1457536638.50787,
        |          "container_status": {
        |            "network_infos": [
        |              {
        |                "ip_address": "192.168.99.10",
        |                "ip_addresses": [
        |                  {
        |                    "ip_address": "192.168.99.10"
        |                  }
        |                ]
        |              }
        |            ]
        |          }
        |        },
        |        {
        |          "state": "TASK_FINISHED",
        |          "timestamp": 1457537638.57408,
        |          "container_status": {
        |            "network_infos": [
        |              {
        |                "ip_address": "192.168.99.10",
        |                "ip_addresses": [
        |                  {
        |                    "ip_address": "192.168.99.10"
        |                  }
        |                ]
        |              }
        |            ]
        |          }
        |        }
        |      ],
        |      "discovery": {
        |        "visibility": "FRAMEWORK",
        |        "name": "resident1",
        |        "ports": {
        |
        |        }
        |      },
        |      "container": {
        |        "type": "MESOS",
        |        "mesos": {
        |
        |        }
        |      }
        |    }
        |  ],
        |  "unregistered_frameworks": [
        |    "70c87ff5-537d-4576-ba03-62e8130787ac-0001",
        |    "ee85eedd-674c-40f9-ae8f-67158a923d65-0000"
        |  ]
        |}
      """.stripMargin
  }
}
