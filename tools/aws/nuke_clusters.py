#!/usr/bin/env python3
import boto3
import logging

from botocore.exceptions import ClientError
from logging import config
from logging import config

logging.config.fileConfig('logging.conf')
logger = logging.getLogger(__name__)

def delete_stacks():
    logger.info('Deleting stacks..')

    cloudformation = boto3.resource('cloudformation')
    for stack in cloudformation.stacks.all():
        stack.delete()
    
    logger.info('Done.')


def delete_volumes():
    logger.info('Delete volumes.')

    ec2 = boto3.resource('ec2')
    for volume in ec2.volumes.all():
        try:
            volume.delete()
        except ClientError:
            logger.exception('Could not delete volume %s', volume.id)

    logger.info('Done.')


def delete_key_pairs():
    logger.info('Delete key pairs.')

    ec2 = boto3.resource('ec2')
    for pair in ec2.key_pairs.all():
        pair.delete()

    logger.info('Done.')


def nuke_clusters():
    delete_stacks()
    delete_volumes()
    delete_key_pairs()


if __name__ == "__main__":

    confirmation = input('You are about to nuke all test clusters. Enter "I know what I\'m doing" to continue:')
    if confirmation == 'I know what I\'m doing':
        boto3.setup_default_session(region_name='us-west-2')
        nuke_clusters()
