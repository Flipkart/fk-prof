import React from 'react';
import { connect } from 'react-redux';
import Select, {Creatable} from 'react-select';

import fetchProfilesAppsAction from 'actions/ProfilesAppActions';
import debounce from 'utils/debounce';
import styles from './AppSelectComponent.css';
import fetchPolicyAppsAction from 'actions/PoliciesAppActions';

const noop = () => {};

class AppSelectComponent extends React.Component {
  componentDidMount() {
    this.props.isPoliciesPage ? this.props.getPoliciesApps() : this.props.getProfilesApps();
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.isPoliciesPage !== this.props.isPoliciesPage) {
      nextProps.isPoliciesPage ? this.props.getPoliciesApps() : this.props.getProfilesApps();
    }
  }

  render() {
    const appsResponse = this.props.isPoliciesPage ? this.props.policiesApps : this.props.profilesApps;
    const apps = appsResponse.asyncStatus === 'SUCCESS'
      ? appsResponse.data.map(a => ({name: a})) : [];

    const noResultsText = appsResponse.asyncStatus === 'SUCCESS'
    && appsResponse.data.length === 0 ? 'No results found!' : 'Searching...';
    const valueOption = this.props.value && {name: this.props.value};
    return (
      <div>
        <label className={styles.label} htmlFor="appid">App</label>
        {this.props.isPoliciesPage &&
        <Creatable
          clearable={false}
          id="appid"
          options={apps}
          value={valueOption}
          onChange={this.props.onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getPoliciesApps, 500)}
          isLoading={appsResponse.asyncStatus === 'PENDING'}
          noResultsText={noResultsText}
          placeholder="Type to search..."
          promptTextCreator={(label) => "Add app: " + label}
          onOpen={()=>(this.props.value && this.creatable.select && this.creatable.select.setState({inputValue: this.props.value}))}
          ref={(ref)=> this.creatable = ref}
        />}
        {!this.props.isPoliciesPage &&
        <Select
          clearable={false}
          id="appid"
          options={apps}
          value={valueOption}
          onChange={this.props.onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getProfilesApps, 500)}
          isLoading={appsResponse.asyncStatus === 'PENDING'}
          noResultsText={noResultsText}
          placeholder="Type to search..."
          onOpen={()=>(this.props.value && this.select && this.select.setState({inputValue: this.props.value}))}
          ref={(ref)=> this.select = ref}
        />}
      </div>
    );
  }
}

const mapStateToProps = state => ({ profilesApps: state.profilesApps, policiesApps: state.policiesApps });
const mapDispatchToProps = dispatch => ({
  getProfilesApps: prefix => dispatch(fetchProfilesAppsAction(prefix)),
  getPoliciesApps: prefix => dispatch(fetchPolicyAppsAction(prefix))
});

export default connect(mapStateToProps, mapDispatchToProps)(AppSelectComponent);

