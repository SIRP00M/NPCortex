tellraw @s {"text":"[parity] status (0=none 1=PASS 2=FAIL 3=NOT TESTED 4=PENDING)","color":"gold"}
execute if score #t01 pp matches 1 run say PARITY|STATUS|T01|PASS
execute if score #t01 pp matches 2 run say PARITY|STATUS|T01|FAIL
execute if score #t01 pp matches 3 run say PARITY|STATUS|T01|NOT TESTED
execute if score #t01 pp matches 4 run say PARITY|STATUS|T01|PENDING
execute unless score #t01 pp matches 1..4 run say PARITY|STATUS|T01|NOT TESTED not run
execute if score #t02 pp matches 1 run say PARITY|STATUS|T02|PASS
execute if score #t02 pp matches 2 run say PARITY|STATUS|T02|FAIL
execute if score #t02 pp matches 3 run say PARITY|STATUS|T02|NOT TESTED
execute if score #t02 pp matches 4 run say PARITY|STATUS|T02|PENDING
execute unless score #t02 pp matches 1..4 run say PARITY|STATUS|T02|NOT TESTED not run
execute if score #t03 pp matches 1 run say PARITY|STATUS|T03|PASS
execute if score #t03 pp matches 2 run say PARITY|STATUS|T03|FAIL
execute if score #t03 pp matches 3 run say PARITY|STATUS|T03|NOT TESTED
execute if score #t03 pp matches 4 run say PARITY|STATUS|T03|PENDING
execute unless score #t03 pp matches 1..4 run say PARITY|STATUS|T03|NOT TESTED not run
execute if score #t04 pp matches 1 run say PARITY|STATUS|T04|PASS
execute if score #t04 pp matches 2 run say PARITY|STATUS|T04|FAIL
execute if score #t04 pp matches 3 run say PARITY|STATUS|T04|NOT TESTED
execute if score #t04 pp matches 4 run say PARITY|STATUS|T04|PENDING
execute unless score #t04 pp matches 1..4 run say PARITY|STATUS|T04|NOT TESTED not run
execute if score #t05 pp matches 1 run say PARITY|STATUS|T05|PASS
execute if score #t05 pp matches 2 run say PARITY|STATUS|T05|FAIL
execute if score #t05 pp matches 3 run say PARITY|STATUS|T05|NOT TESTED
execute if score #t05 pp matches 4 run say PARITY|STATUS|T05|PENDING
execute unless score #t05 pp matches 1..4 run say PARITY|STATUS|T05|NOT TESTED not run
execute if score #t06 pp matches 1 run say PARITY|STATUS|T06|PASS
execute if score #t06 pp matches 2 run say PARITY|STATUS|T06|FAIL
execute if score #t06 pp matches 3 run say PARITY|STATUS|T06|NOT TESTED
execute if score #t06 pp matches 4 run say PARITY|STATUS|T06|PENDING
execute unless score #t06 pp matches 1..4 run say PARITY|STATUS|T06|NOT TESTED not run
execute if score #t07 pp matches 1 run say PARITY|STATUS|T07|PASS
execute if score #t07 pp matches 2 run say PARITY|STATUS|T07|FAIL
execute if score #t07 pp matches 3 run say PARITY|STATUS|T07|NOT TESTED
execute if score #t07 pp matches 4 run say PARITY|STATUS|T07|PENDING
execute unless score #t07 pp matches 1..4 run say PARITY|STATUS|T07|NOT TESTED not run
execute if score #t08 pp matches 1 run say PARITY|STATUS|T08|PASS
execute if score #t08 pp matches 2 run say PARITY|STATUS|T08|FAIL
execute if score #t08 pp matches 3 run say PARITY|STATUS|T08|NOT TESTED
execute if score #t08 pp matches 4 run say PARITY|STATUS|T08|PENDING
execute unless score #t08 pp matches 1..4 run say PARITY|STATUS|T08|NOT TESTED not run
execute if score #t09 pp matches 1 run say PARITY|STATUS|T09|PASS
execute if score #t09 pp matches 2 run say PARITY|STATUS|T09|FAIL
execute if score #t09 pp matches 3 run say PARITY|STATUS|T09|NOT TESTED
execute if score #t09 pp matches 4 run say PARITY|STATUS|T09|PENDING
execute unless score #t09 pp matches 1..4 run say PARITY|STATUS|T09|NOT TESTED not run
execute if score #t10 pp matches 1 run say PARITY|STATUS|T10|PASS
execute if score #t10 pp matches 2 run say PARITY|STATUS|T10|FAIL
execute if score #t10 pp matches 3 run say PARITY|STATUS|T10|NOT TESTED
execute if score #t10 pp matches 4 run say PARITY|STATUS|T10|PENDING
execute unless score #t10 pp matches 1..4 run say PARITY|STATUS|T10|NOT TESTED not run
execute if score #t11 pp matches 1 run say PARITY|STATUS|T11|PASS
execute if score #t11 pp matches 2 run say PARITY|STATUS|T11|FAIL
execute if score #t11 pp matches 3 run say PARITY|STATUS|T11|NOT TESTED
execute if score #t11 pp matches 4 run say PARITY|STATUS|T11|PENDING
execute unless score #t11 pp matches 1..4 run say PARITY|STATUS|T11|NOT TESTED not run
execute if score #t12 pp matches 1 run say PARITY|STATUS|T12|PASS
execute if score #t12 pp matches 2 run say PARITY|STATUS|T12|FAIL
execute if score #t12 pp matches 3 run say PARITY|STATUS|T12|NOT TESTED
execute if score #t12 pp matches 4 run say PARITY|STATUS|T12|PENDING
execute unless score #t12 pp matches 1..4 run say PARITY|STATUS|T12|NOT TESTED not run
execute if score #t13 pp matches 1 run say PARITY|STATUS|T13|PASS
execute if score #t13 pp matches 2 run say PARITY|STATUS|T13|FAIL
execute if score #t13 pp matches 3 run say PARITY|STATUS|T13|NOT TESTED
execute if score #t13 pp matches 4 run say PARITY|STATUS|T13|PENDING
execute unless score #t13 pp matches 1..4 run say PARITY|STATUS|T13|NOT TESTED not run
execute if score #t14 pp matches 1 run say PARITY|STATUS|T14|PASS
execute if score #t14 pp matches 2 run say PARITY|STATUS|T14|FAIL
execute if score #t14 pp matches 3 run say PARITY|STATUS|T14|NOT TESTED
execute if score #t14 pp matches 4 run say PARITY|STATUS|T14|PENDING
execute unless score #t14 pp matches 1..4 run say PARITY|STATUS|T14|NOT TESTED not run
execute if score #t15 pp matches 1 run say PARITY|STATUS|T15|PASS
execute if score #t15 pp matches 2 run say PARITY|STATUS|T15|FAIL
execute if score #t15 pp matches 3 run say PARITY|STATUS|T15|NOT TESTED
execute if score #t15 pp matches 4 run say PARITY|STATUS|T15|PENDING
execute unless score #t15 pp matches 1..4 run say PARITY|STATUS|T15|NOT TESTED not run
execute if score #t16 pp matches 1 run say PARITY|STATUS|T16|PASS
execute if score #t16 pp matches 2 run say PARITY|STATUS|T16|FAIL
execute if score #t16 pp matches 3 run say PARITY|STATUS|T16|NOT TESTED
execute if score #t16 pp matches 4 run say PARITY|STATUS|T16|PENDING
execute unless score #t16 pp matches 1..4 run say PARITY|STATUS|T16|NOT TESTED not run
execute if score #t17 pp matches 1 run say PARITY|STATUS|T17|PASS
execute if score #t17 pp matches 2 run say PARITY|STATUS|T17|FAIL
execute if score #t17 pp matches 3 run say PARITY|STATUS|T17|NOT TESTED
execute if score #t17 pp matches 4 run say PARITY|STATUS|T17|PENDING
execute unless score #t17 pp matches 1..4 run say PARITY|STATUS|T17|NOT TESTED not run
execute if score #t18 pp matches 1 run say PARITY|STATUS|T18|PASS
execute if score #t18 pp matches 2 run say PARITY|STATUS|T18|FAIL
execute if score #t18 pp matches 3 run say PARITY|STATUS|T18|NOT TESTED
execute if score #t18 pp matches 4 run say PARITY|STATUS|T18|PENDING
execute unless score #t18 pp matches 1..4 run say PARITY|STATUS|T18|NOT TESTED not run
execute if score #t19 pp matches 1 run say PARITY|STATUS|T19|PASS
execute if score #t19 pp matches 2 run say PARITY|STATUS|T19|FAIL
execute if score #t19 pp matches 3 run say PARITY|STATUS|T19|NOT TESTED
execute if score #t19 pp matches 4 run say PARITY|STATUS|T19|PENDING
execute unless score #t19 pp matches 1..4 run say PARITY|STATUS|T19|NOT TESTED not run
execute if score #t20 pp matches 1 run say PARITY|STATUS|T20|PASS
execute if score #t20 pp matches 2 run say PARITY|STATUS|T20|FAIL
execute if score #t20 pp matches 3 run say PARITY|STATUS|T20|NOT TESTED
execute if score #t20 pp matches 4 run say PARITY|STATUS|T20|PENDING
execute unless score #t20 pp matches 1..4 run say PARITY|STATUS|T20|NOT TESTED not run
execute if score #t21 pp matches 1 run say PARITY|STATUS|T21|PASS
execute if score #t21 pp matches 2 run say PARITY|STATUS|T21|FAIL
execute if score #t21 pp matches 3 run say PARITY|STATUS|T21|NOT TESTED
execute if score #t21 pp matches 4 run say PARITY|STATUS|T21|PENDING
execute unless score #t21 pp matches 1..4 run say PARITY|STATUS|T21|NOT TESTED not run
execute if score #t22 pp matches 1 run say PARITY|STATUS|T22|PASS
execute if score #t22 pp matches 2 run say PARITY|STATUS|T22|FAIL
execute if score #t22 pp matches 3 run say PARITY|STATUS|T22|NOT TESTED
execute if score #t22 pp matches 4 run say PARITY|STATUS|T22|PENDING
execute unless score #t22 pp matches 1..4 run say PARITY|STATUS|T22|NOT TESTED not run
execute if score #t23 pp matches 1 run say PARITY|STATUS|T23|PASS
execute if score #t23 pp matches 2 run say PARITY|STATUS|T23|FAIL
execute if score #t23 pp matches 3 run say PARITY|STATUS|T23|NOT TESTED
execute if score #t23 pp matches 4 run say PARITY|STATUS|T23|PENDING
execute unless score #t23 pp matches 1..4 run say PARITY|STATUS|T23|NOT TESTED not run
execute if score #t24 pp matches 1 run say PARITY|STATUS|T24|PASS
execute if score #t24 pp matches 2 run say PARITY|STATUS|T24|FAIL
execute if score #t24 pp matches 3 run say PARITY|STATUS|T24|NOT TESTED
execute if score #t24 pp matches 4 run say PARITY|STATUS|T24|PENDING
execute unless score #t24 pp matches 1..4 run say PARITY|STATUS|T24|NOT TESTED not run
execute if score #t25 pp matches 1 run say PARITY|STATUS|T25|PASS
execute if score #t25 pp matches 2 run say PARITY|STATUS|T25|FAIL
execute if score #t25 pp matches 3 run say PARITY|STATUS|T25|NOT TESTED
execute if score #t25 pp matches 4 run say PARITY|STATUS|T25|PENDING
execute unless score #t25 pp matches 1..4 run say PARITY|STATUS|T25|NOT TESTED not run
