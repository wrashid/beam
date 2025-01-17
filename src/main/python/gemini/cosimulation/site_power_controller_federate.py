# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import collections.abc
import helics as h
import itertools
import logging
import sys
import pandas as pd
from threading import Thread
import json
import pathlib

from site_power_controller_utils import DefaultSPMC
from site_power_controller_utils import RideHailSPMC
from site_power_controller_utils import create_federate
from site_power_controller_utils import print2


def run_spm_federate(cfed, time_bin_in_seconds, simulated_day_in_seconds, multi_threaded_run):
    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    fed_name = h.helicsFederateGetName(cfed)

    def print_inf(any_to_print):
        to_print = f"{fed_name}: {str(any_to_print)}"
        logging.info(to_print)
        print(to_print)

    def print_err(any_to_print):
        to_print = f"{fed_name}: {str(any_to_print)}"
        logging.error(to_print)
        print(to_print)

    print_inf("In execution mode")
    subs_charging_events = h.helicsFederateGetInputByIndex(cfed, 0)
    pubs_control = h.helicsFederateGetPublicationByIndex(cfed, 0)

    # in case of multiple federates it is still fine to have these dicts as local variables
    # because each federate will work with a fixed subset of TAZs
    default_spm_c_dict = {}
    ride_hail_spm_c_dict = {}
    depot_prefix = "depot"

    def key_func(k):
        return k['siteId']

    def sync_time(requested_time):
        granted_time = -1
        while granted_time < requested_time:
            granted_time = h.helicsFederateRequestTime(cfed, requested_time)

    def parse_json(message_to_parse):
        try:
            return json.loads(message_to_parse)
        except json.decoder.JSONDecodeError as err:
            print_err("Message from BEAM is an incorrect JSON, " + str(err))
            return ""

    # INIT
    def init_spm_controllers(site_id_str):
        if site_id_str not in default_spm_c_dict:
            default_spm_c_dict[site_id_str] = DefaultSPMC("DefaultSPMC", site_id_str)
        if site_id_str not in ride_hail_spm_c_dict:
            ride_hail_spm_c_dict[site_id_str] = RideHailSPMC("RideHailSPMC", site_id_str)

    # RUN
    def run_multi_threaded_spm_controllers(site_id_str, current_t, received_charging_events):
        if not site_id_str.lower().startswith(depot_prefix):
            default_spm_c_dict[site_id_str].run_as_thread(current_t, received_charging_events)
        else:
            ride_hail_spm_c_dict[site_id_str].run_as_thread(current_t, received_charging_events)

    def run_spm_controllers(site_id_str, current_t, received_charging_events):
        if not site_id_str.lower().startswith(depot_prefix):
            default_spm_c_dict[site_id_str].run(current_t, received_charging_events)
        else:
            ride_hail_spm_c_dict[site_id_str].run(current_t, received_charging_events)

    # CONTROL COMMANDS
    def get_power_commands(site_id_str):
        if not site_id_str.lower().startswith(depot_prefix):
            return default_spm_c_dict[site_id_str].get_output_from_latest_run()
        else:
            return ride_hail_spm_c_dict[site_id_str].get_output_from_latest_run()

    # start execution loop
    for t in range(0, simulated_day_in_seconds - time_bin_in_seconds, time_bin_in_seconds):
        sync_time(t)
        power_commands_list = []
        received_message = h.helicsInputGetString(subs_charging_events)
        site_id_counter = 0
        charging_events_counter = 0
        if bool(str(received_message).strip()):
            charging_events_json = parse_json(received_message)
            if not isinstance(charging_events_json, collections.abc.Sequence):
                print_err(f"[time:{str(t)}] It was not able to parse JSON message from BEAM: " + received_message)
                pass
            elif len(charging_events_json) > 0:
                processed_side_ids = []
                for site_id, charging_events in itertools.groupby(charging_events_json, key_func):
                    init_spm_controllers(site_id)
                    # Running SPM Controllers
                    filtered_charging_events = list(
                        filter(lambda charging_event: 'vehicleId' in charging_event, charging_events))
                    if len(filtered_charging_events) > 0:
                        processed_side_ids = processed_side_ids + [site_id]
                        if multi_threaded_run:
                            run_multi_threaded_spm_controllers(site_id, t, filtered_charging_events)
                        else:
                            run_spm_controllers(site_id, t, filtered_charging_events)
                        site_id_counter = site_id_counter + 1
                        charging_events_counter = charging_events_counter + len(filtered_charging_events)
                for site_id in processed_side_ids:
                    power_commands_list = power_commands_list + get_power_commands(site_id)
            else:
                # print_err("[time:" + str(t) + "] The JSON message is empty")
                pass
        else:
            # print_err("[time:" + str(t) + "] SPM Controller received empty message from BEAM!")
            pass
        h.helicsPublicationPublishString(pubs_control, json.dumps(power_commands_list, separators=(',', ':')))
        if len(power_commands_list) > 0:
            pd.DataFrame(power_commands_list).to_csv('out.csv', mode='a', index=False, header=False)
        sync_time(t + 1)
        if t % 1800 == 0:
            print2("Hour " + str(t/3600) + " completed.")

    # close the helics federate
    h.helicsFederateDisconnect(cfed)
    print_inf("Federate finalized and now saving and finishing")
    h.helicsFederateFree(cfed)
    # depotController: save results
    # TODO uncomment
    # depotController.save()
    print_inf("Finished")


###############################################################################

if __name__ == "__main__":
    number_of_federates = 1
    if len(sys.argv) > 1:
        number_of_federates = int(sys.argv[1])

    current_directory = str(pathlib.Path(__file__).parent.resolve())
    log_file = current_directory + "/site_power_controller_federate.log"
    print("Log file will located at: " + log_file)
    logging.basicConfig(filename=log_file, level=logging.DEBUG, filemode='w')
    print2("Using helics version " + h.helicsGetVersion())
    helics_config = {"coreInitString": f"--federates={number_of_federates} --broker_address=tcp://127.0.0.1",
                     "coreType": "zmq",
                     "timeDeltaProperty": 1.0,  # smallest discernible interval to this federate
                     "intLogLevel": 1,
                     "federatesPrefix": "BEAM_FED",
                     "federatesPublication": "CHARGING_VEHICLES",
                     "spmFederatesPrefix": "SPM_FED",
                     "spmSubscription": "CHARGING_COMMANDS",
                     "timeStepInSeconds": 60}

    federate_ids = list(map(lambda x: str(x), range(number_of_federates)))

    print2(f"Creating {number_of_federates} federate(s) ...")
    main_fed_info = h.helicsCreateFederateInfo()
    # set core type
    h.helicsFederateInfoSetCoreTypeFromString(main_fed_info, helics_config["coreType"])
    # set initialization string
    h.helicsFederateInfoSetCoreInitString(main_fed_info, helics_config["coreInitString"])
    # set message interval
    h.helicsFederateInfoSetTimeProperty(main_fed_info, h.helics_property_time_delta, helics_config["timeDeltaProperty"])
    #
    h.helicsFederateInfoSetIntegerProperty(main_fed_info, h.helics_property_int_log_level, helics_config["intLogLevel"])

    feds = [create_federate(helics_config, main_fed_info, fed_id) for fed_id in federate_ids]
    print2("Starting " + str(len(feds)) + " thread(s). Each thread is running one federate.")

    time_bin = helics_config["timeStepInSeconds"]
    simulated_day = 60 * 3600  # 60 hours BEAM Day

    # start execution loop
    threads = []
    for fed in feds:
        thread = Thread(target=run_spm_federate, args=(fed, time_bin, simulated_day, False))
        thread.start()
        threads.append(thread)

    # closing helics after all federates are finished
    for thread in threads:
        thread.join()

    print2("Closing Helics...")
    h.helicsCloseLibrary()
    print2("Finished.")
