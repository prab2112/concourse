<?php

/* 
 * Copyright 2015 Cinchapi, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Central place to require everything that needs to be autoloaded throughout
 * the project.
 */

require_once dirname(__FILE__) . "/../../../vendor/autoload.php";
require_once dirname(__FILE__) . "/thrift/ConcourseService.php";
require_once dirname(__FILE__) . "/thrift/shared/Types.php";
require_once dirname(__FILE__) . "/thrift/data/Types.php";
require_once dirname(__FILE__) . "/Concourse.php";
require_once dirname(__FILE__) . "/Convert.php";
require_once dirname(__FILE__) . "/Tag.php";
require_once dirname(__FILE__) . "/Link.php";
require_once dirname(__FILE__) . "/utils.php";
