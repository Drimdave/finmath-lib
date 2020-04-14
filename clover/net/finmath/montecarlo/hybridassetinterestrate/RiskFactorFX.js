var clover = new Object();

// JSON: {classes : [{name, id, sl, el,  methods : [{sl, el}, ...]}, ...]}
clover.pageData = {"classes":[{"el":16,"id":19371,"methods":[{"el":10,"sc":2,"sl":7},{"el":15,"sc":2,"sl":12}],"name":"RiskFactorFX","sl":3}]}

// JSON: {test_ID : {"methods": [ID1, ID2, ID3...], "name" : "testXXX() void"}, ...};
clover.testTargets = {"test_312":{"methods":[{"sl":7},{"sl":12}],"name":"testProperties","pass":true,"statements":[{"sl":8},{"sl":9},{"sl":14}]},"test_395":{"methods":[{"sl":7},{"sl":12}],"name":"testForeignBond","pass":true,"statements":[{"sl":8},{"sl":9},{"sl":14}]},"test_489":{"methods":[{"sl":7},{"sl":12}],"name":"testForeignCaplet","pass":true,"statements":[{"sl":8},{"sl":9},{"sl":14}]},"test_97":{"methods":[{"sl":7},{"sl":12}],"name":"testForeignFRA","pass":true,"statements":[{"sl":8},{"sl":9},{"sl":14}]}}

// JSON: { lines : [{tests : [testid1, testid2, testid3, ...]}, ...]};
clover.srcFileLines = [[], [], [], [], [], [], [], [312, 97, 395, 489], [312, 97, 395, 489], [312, 97, 395, 489], [], [], [312, 97, 395, 489], [], [312, 97, 395, 489], [], []]