<!-- NOTE: original sketch. The current, authoritative architecture + decisions live in
context/plan.md and context/decisions.md. Notably evolved since: the "linux server" is a
Yandex Cloud VM (Terraform), plus a tea catalog + AI enrichment. -->

we want to have
1 mobile client.
2 linux server.
3 database server(if needed)

language of app and backend is Kotlin, mobile app also kotlin

disign should me minimal where needed, not bloated, each feature should be clear and with reasonable scenario.